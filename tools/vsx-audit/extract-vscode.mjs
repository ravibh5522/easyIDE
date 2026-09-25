#!/usr/bin/env node
// Extract the VS Code extension-facing surface from a microsoft/vscode source checkout into JSON.
//
// Usage:
//   node tools/vsx-audit/extract-vscode.mjs --vscode <path-to-clone> --out docs/vsx-compat/data \
//        [--product <stable product.json>] [--docs-cache <dir>] [--refresh-docs]
//
//   --vscode        microsoft/vscode checkout (a shallow clone is enough; git is used for sha/date)
//   --out           output directory for vscode-*.json
//   --product       optional product.json of a Microsoft release build (the OSS repo product.json has no
//                   extensionEnabledApiProposals); e.g. extracted from vscode-server-linux-x64.tar.gz
//   --docs-cache    optional directory to cache the code.visualstudio.com reference pages (raw .md);
//                   without it the pages are fetched on every run
//   --refresh-docs  re-fetch docs even when cached
//
// Output is deterministic for a given (vscode commit, docs content, product.json): arrays are sorted,
// no timestamps other than the vscode commit date are written. Docs are identified by sha256.
// Requires: `npm install` in tools/vsx-audit (typescript).
import fs from 'node:fs';
import path from 'node:path';
import ts from 'typescript';
import { gitInfo, writeJson, fileStats } from './lib/vscode-util.mjs';
import { extractApi, extractProposed } from './lib/vscode-api.mjs';
import { scanSource } from './lib/vscode-scan.mjs';
import { loadDoc, docMeta } from './lib/vscode-docs.mjs';
import { buildContributes, buildActivationEvents, buildMenus } from './lib/vscode-contrib.mjs';
import { buildContextKeys } from './lib/vscode-context.mjs';
import { buildExtHostShapes, buildBuiltinCommands } from './lib/vscode-exthost.mjs';

const EXTENSION_IDS = [
	'eamodio.gitlens', 'anthropic.claude-code', 'github.vscode-pull-request-github', 'ms-python.python', 'redhat.java',
	'golang.go', 'rust-lang.rust-analyzer', 'dbaeumer.vscode-eslint', 'vue.volar', 'ms-azuretools.vscode-docker',
];

function parseArgs(argv) {
	const a = { refreshDocs: false };
	for (let i = 0; i < argv.length; i++) {
		const k = argv[i];
		const next = () => { const v = argv[++i]; if (v === undefined) { throw new Error(`missing value for ${k}`); } return v; };
		if (k === '--vscode') { a.vscode = next(); }
		else if (k === '--out') { a.out = next(); }
		else if (k === '--product') { a.product = next(); }
		else if (k === '--docs-cache') { a.docsCache = next(); }
		else if (k === '--refresh-docs') { a.refreshDocs = true; }
		else if (k === '-h' || k === '--help') { a.help = true; }
		else { throw new Error(`unknown argument ${k}`); }
	}
	return a;
}

async function main() {
	const args = parseArgs(process.argv.slice(2));
	if (args.help || !args.vscode || !args.out) {
		console.log('usage: node tools/vsx-audit/extract-vscode.mjs --vscode <clone> --out <dir> [--product <product.json>] [--docs-cache <dir>] [--refresh-docs]');
		process.exit(args.help ? 0 : 2);
	}
	const vs = path.resolve(args.vscode);
	const out = path.resolve(args.out);
	const git = gitInfo(vs);
	const pkg = JSON.parse(fs.readFileSync(path.join(vs, 'package.json'), 'utf8'));
	const baseMeta = {
		vscodeCommit: git.sha,
		vscodeCommitDate: git.date,
		vscodeVersion: pkg.version,
		generatedBy: `tools/vsx-audit/extract-vscode.mjs (typescript ${ts.version})`,
	};
	const meta = (source, extra = {}) => ({ ...baseMeta, source, ...extra });
	const log = (m) => process.stderr.write(m + '\n');

	const docs = {};
	for (const p of ['contribution-points', 'activation-events', 'when-clause-contexts', 'commands']) {
		docs[p] = await loadDoc(p, args.docsCache && path.resolve(args.docsCache), args.refreshDocs);
	}
	const stableProduct = args.product ? JSON.parse(fs.readFileSync(args.product, 'utf8')) : undefined;
	const counts = {};
	const emit = (name, obj) => { writeJson(path.join(out, name), obj); counts[name] = obj.items.length; };

	// 1. API
	log('vscode.d.ts ...');
	const api = extractApi(vs);
	emit('vscode-api.json', { meta: meta(['src/vscode-dts/vscode.d.ts'], { summary: api.summary }), items: api.items });
	const products = [{ label: 'oss-repo', file: path.join(vs, 'product.json') }];
	if (args.product) { products.push({ label: 'microsoft-release', file: path.resolve(args.product) }); }
	const prop = extractProposed(vs, products, EXTENSION_IDS);
	// avoid absolute local paths in output
	for (const p of prop.products) { p.file = p.label === 'oss-repo' ? 'product.json' : `(--product) ${path.basename(p.file)}`; }
	emit('vscode-proposed.json', {
		meta: meta(['src/vscode-dts/vscode.proposed.*.d.ts', prop.registryRef, 'product.json'], {
			summary: { proposedDtsFiles: prop.items.length, registryEntries: prop.registryCount, registryOnly: prop.registryOnly, totalLines: prop.items.reduce((a, i) => a + i.lines, 0) },
			productEnabledApiProposals: prop.products,
		}),
		items: prop.items,
	});

	// shared source scan
	log('scanning src/vs ...');
	const facts = scanSource(vs);
	log(`  ${facts.files.length} files`);

	// 2. contributes
	const contrib = buildContributes(vs, facts, docs['contribution-points'], stableProduct);
	emit('vscode-contributes.json', { meta: meta(['src/vs/**: ExtensionsRegistry.registerExtensionPoint descriptors ({extensionPoint, jsonSchema})', docMeta(docs['contribution-points'])], { summary: contrib.summary }), items: contrib.items });

	// 3. activation events
	const ae = buildActivationEvents(vs, facts, docs['activation-events']);
	emit('vscode-activation-events.json', { meta: meta(['src/vs/workbench/services/extensions/common/extensionsRegistry.ts (activationEvents schema)', 'activationEventsGenerator yields', 'activateByEvent() call sites', docMeta(docs['activation-events'])], { summary: ae.summary }), items: ae.items });

	// 4. context keys
	const ck = buildContextKeys(vs, facts, docs['when-clause-contexts']);
	emit('vscode-when-context.json', { meta: meta(['src/vs/**: new RawContextKey(...), *.createKey(...), ContextKeyExpr.*(...)', 'src/vs/platform/contextkey/common/{scanner,contextkey}.ts', docMeta(docs['when-clause-contexts'])], { summary: ck.summary }), operators: ck.operators, items: ck.items });

	// 5. menus
	const menus = buildMenus(vs, docs['contribution-points']);
	emit('vscode-menus.json', { meta: meta(['src/vs/workbench/services/actions/common/menusExtensionPoint.ts (apiMenus)', 'src/vs/platform/actions/common/actions.ts (MenuId)', docMeta(docs['contribution-points'])], { summary: menus.summary }), items: menus.items });

	// 6. ext host shapes
	const eh = buildExtHostShapes(vs, facts);
	emit('vscode-exthost-shapes.json', { meta: meta(['src/vs/workbench/api/common/extHost.protocol.ts', '@extHostNamedCustomer(MainContext.*) + class implements *Shape in src/vs'], { summary: eh.summary }), proxyIdentifiers: eh.proxyIdentifiers, items: eh.items });

	// 7. built-in commands
	const bc = buildBuiltinCommands(vs, facts, docs['commands']);
	emit('vscode-builtin-commands.json', { meta: meta([docMeta(docs['commands']), 'src/vs/workbench/api/common/extHostApiCommands.ts', 'src/vs/** registerCommand / id: literals'], { summary: bc.summary }), items: bc.items });

	// validate: parse every file back
	for (const name of Object.keys(counts)) {
		const back = JSON.parse(fs.readFileSync(path.join(out, name), 'utf8'));
		if (!Array.isArray(back.items) || back.items.length !== counts[name] || !back.meta.vscodeCommit) { throw new Error(`validation failed for ${name}`); }
		log(`${name}: ${counts[name]} items (${fileStats(out, name).bytes} bytes)`);
	}
}

main().catch(e => { console.error(e); process.exit(1); });
