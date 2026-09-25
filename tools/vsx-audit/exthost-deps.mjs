#!/usr/bin/env node
// exthost-deps.mjs - static import-closure analyser for VS Code's extension host.
//
// Usage:
//   node tools/vsx-audit/exthost-deps.mjs --vscode <microsoft/vscode clone> \
//        [--out docs/vsx-compat/data/exthost-deps.json] [--entry vs/a/b/c ...] [--top 30]
//        [--churn 1.130.0,1.131.0,...]   (repeatable; consecutive-tag churn, see lib/deps-churn.mjs)
//
// For each entry module (default: the node extension host process, the API factory and the
// node service registrations) it follows static `import ... from`, side-effect `import '...'`
// and `export ... from` declarations (type-only imports excluded; dynamic `import()` recorded
// but not followed), resolving relative and `vs/...` specifiers to src/vs/**.ts. Bare
// specifiers are reported as npm packages or node builtins.
//
// Parser: the `typescript` package, resolved from <vscode>/node_modules, then this tool's own
// node_modules, then NODE_PATH. If none is found a comment/string-aware regex scanner is used;
// the parser actually used is recorded in the output ("parser").
//
// The closure is an upper bound of what a bundler includes: esbuild additionally drops imports
// whose bindings are used only as types and tree-shakes unused exports. Zero npm dependencies.

import fs from 'node:fs';
import path from 'node:path';
import { createRequire, builtinModules } from 'node:module';
import { fileURLToPath } from 'node:url';
import { analyseProtocol } from './lib/deps-protocol.mjs';
import { analyseChurn } from './lib/deps-churn.mjs';

const DEFAULT_ENTRIES = [
	'vs/workbench/api/node/extensionHostProcess',
	'vs/workbench/api/common/extHost.api.impl',
	'vs/workbench/api/node/extHost.node.services',
];

// Path-substring patterns identifying heavy subsystems (first match wins per pattern, a file may
// belong to several subsystems).
const SUBSYSTEMS = {
	'editor-model': [/^vs\/editor\/common\/model\//, /^vs\/editor\/common\/model\.ts$/, /^vs\/editor\/common\/core\//],
	'editor-languages': [/^vs\/editor\/common\/languages/, /^vs\/editor\/common\/tokens/],
	'editor-other': [/^vs\/editor\/(?!common\/(model|core|languages|tokens))/],
	'textmate/tree-sitter': [/textMate/i, /treeSitter/i, /vscode-textmate|vscode-oniguruma/],
	telemetry: [/\/telemetry\//, /[Tt]elemetry/],
	debug: [/contrib\/debug\//, /[Dd]ebug(Service|Protocol|Adapter)/, /extHostDebug/],
	notebook: [/[Nn]otebook/],
	'chat/lm/mcp/agents': [/contrib\/chat\//, /[Cc]hat/, /[Ll]anguageModel/, /[Mm]cp/, /agentHost|[Aa]gentSessions/],
	terminal: [/[Tt]erminal/],
	testing: [/contrib\/testing\//, /extHostTesting|extHostTestItem/],
	tasks: [/contrib\/tasks\//, /extHostTask/],
	search: [/services\/search\/|contrib\/search\//, /extHostSearch/],
	scm: [/contrib\/scm\//, /extHostSCM|extHostQuickDiff/],
	webview: [/[Ww]ebview/],
	'remote/tunnels': [/[Tt]unnel/, /platform\/remote\//],
	'files/watcher': [/platform\/files\//],
	'ipc/protocol': [/base\/parts\/ipc\//, /services\/extensions\/common\/(rpcProtocol|proxyIdentifier|extensionHostProtocol)/],
	'nls/l10n': [/^vs\/nls/, /[Ll]ocalization/],
	'extension-mgmt': [/platform\/extensionManagement\//, /platform\/extensions\//, /services\/extensions\//],
};

function parseArgs(argv) {
	const a = { entries: [], top: 30 };
	for (let i = 0; i < argv.length; i++) {
		const k = argv[i];
		if (k === '--vscode') a.vscode = argv[++i];
		else if (k === '--out') a.out = argv[++i];
		else if (k === '--entry') a.entries.push(argv[++i]);
		else if (k === '--top') a.top = Number(argv[++i]);
		else if (k === '--churn') a.churn = (a.churn ?? []).concat([argv[++i].split(',')]);
		else if (k === '-h' || k === '--help') a.help = true;
		else throw new Error(`unknown argument ${k}`);
	}
	if (!a.entries.length) a.entries = DEFAULT_ENTRIES;
	return a;
}

function loadTypeScript(vscodeDir) {
	const here = path.dirname(fileURLToPath(import.meta.url));
	const bases = [path.join(vscodeDir, 'package.json'), path.join(here, 'package.json'), path.join(process.cwd(), 'package.json')];
	for (const p of (process.env.NODE_PATH || '').split(path.delimiter).filter(Boolean)) bases.push(path.join(p, '..', 'package.json'));
	for (const base of bases) {
		try {
			const ts = createRequire(base)('typescript');
			if (typeof ts.createSourceFile === 'function') return ts;
		} catch { /* try next */ }
	}
	return null;
}

// Fallback: strip comments and template/string bodies conservatively, then match import forms.
function scanRegex(text) {
	const out = [];
	const noComments = text.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:'"`\\])\/\/.*$/gm, '$1');
	const re = /^\s*(import|export)\s+(type\s+)?([\s\S]*?)\s*from\s*['"]([^'"]+)['"]|^\s*import\s*['"]([^'"]+)['"]|import\(\s*['"]([^'"]+)['"]\s*\)/gm;
	let m;
	while ((m = re.exec(noComments))) {
		if (m[6]) { out.push({ spec: m[6], kind: 'dynamic' }); continue; }
		if (m[5]) { out.push({ spec: m[5], kind: 'side-effect' }); continue; }
		if (m[2]) { out.push({ spec: m[4], kind: 'type' }); continue; }
		const clause = m[3].trim();
		const inner = clause.match(/^\{([\s\S]*)\}$/);
		if (inner && inner[1].split(',').map(s => s.trim()).filter(Boolean).every(s => s.startsWith('type '))) {
			out.push({ spec: m[4], kind: 'type' });
		} else {
			out.push({ spec: m[4], kind: m[1] === 'export' ? 're-export' : 'static' });
		}
	}
	return out;
}

function scanTs(ts, file, text) {
	const sf = ts.createSourceFile(file, text, ts.ScriptTarget.Latest, false, ts.ScriptKind.TS);
	const out = [];
	for (const st of sf.statements) {
		if (ts.isImportDeclaration(st) && ts.isStringLiteral(st.moduleSpecifier)) {
			const c = st.importClause;
			let kind = 'static';
			if (!c) kind = 'side-effect';
			else if (c.isTypeOnly) kind = 'type';
			else if (!c.name && c.namedBindings && ts.isNamedImports(c.namedBindings) && c.namedBindings.elements.length > 0
				&& c.namedBindings.elements.every(e => e.isTypeOnly)) kind = 'type';
			out.push({ spec: st.moduleSpecifier.text, kind });
		} else if (ts.isExportDeclaration(st) && st.moduleSpecifier && ts.isStringLiteral(st.moduleSpecifier)) {
			out.push({ spec: st.moduleSpecifier.text, kind: st.isTypeOnly ? 'type' : 're-export' });
		} else if (ts.isImportEqualsDeclaration(st) && ts.isExternalModuleReference(st.moduleReference)
			&& ts.isStringLiteral(st.moduleReference.expression)) {
			out.push({ spec: st.moduleReference.expression.text, kind: 'static' });
		}
	}
	const visit = (n) => {
		if (ts.isCallExpression(n) && n.expression.kind === ts.SyntaxKind.ImportKeyword && n.arguments.length && ts.isStringLiteralLike(n.arguments[0])) {
			out.push({ spec: n.arguments[0].text, kind: 'dynamic' });
		}
		ts.forEachChild(n, visit);
	};
	visit(sf);
	return out;
}

const BUILTINS = new Set(builtinModules.flatMap(m => [m, `node:${m}`]));

function npmName(spec) {
	const parts = spec.split('/');
	return spec.startsWith('@') ? parts.slice(0, 2).join('/') : parts[0];
}

function layerOf(id) {
	for (const l of ['vs/base', 'vs/platform', 'vs/editor', 'vs/workbench']) if (id.startsWith(l + '/')) return l;
	return 'other';
}

function countLoc(text) {
	let n = 0;
	for (const line of text.split('\n')) if (line.trim()) n++;
	return n;
}

function analyse(srcDir, entry, scan) {
	const files = new Map(); // id -> {loc, lines, bytes}
	const npm = new Map(); // pkg -> Set(importer)
	const builtins = new Set();
	const dynamic = [];
	const css = new Set();
	const unresolved = [];
	const vendored = [];
	const declOnly = [];
	const typeOnlyEdges = { count: 0 };
	const queue = [entry];
	while (queue.length) {
		const id = queue.pop();
		if (files.has(id)) continue;
		const base = path.join(srcDir, id);
		let file = base + '.ts';
		let text;
		if (!fs.existsSync(file) && fs.existsSync(base + '.js')) {
			// vendored third-party JS inside src/ (e.g. marked, semver); note its cgmanifest licence
			file = base + '.js';
			text = fs.readFileSync(file, 'utf8');
			let licence = null;
			try {
				const cg = JSON.parse(fs.readFileSync(path.join(path.dirname(base), 'cgmanifest.json'), 'utf8'));
				licence = cg.registrations.map(r => `${r.component.git?.name ?? r.component.npm?.name ?? '?'}@${r.version ?? '?'} ${r.license ?? '?'}`).join('; ');
			} catch { /* no manifest */ }
			vendored.push({ file: `src/${id}.js`, loc: countLoc(text), licence });
			files.set(id, { loc: countLoc(text), lines: text.split('\n').length, bytes: Buffer.byteLength(text), js: true });
			continue; // vendored bundles are self-contained
		}
		if (!fs.existsSync(file) && fs.existsSync(base + '.d.ts')) { declOnly.push(id); files.set(id, null); continue; }
		try { text = fs.readFileSync(file, 'utf8'); } catch { unresolved.push({ id, reason: 'missing' }); files.set(id, null); continue; }
		files.set(id, { loc: countLoc(text), lines: text.split('\n').length, bytes: Buffer.byteLength(text) });
		for (const { spec, kind } of scan(file, text)) {
			if (kind === 'type') { typeOnlyEdges.count++; continue; }
			let target = null;
			if (spec.startsWith('.')) target = path.posix.normalize(path.posix.join(path.posix.dirname(id), spec));
			else if (spec.startsWith('vs/')) target = spec;
			if (target) {
				if (target.endsWith('.css')) { css.add(target); continue; }
				target = target.replace(/\.(js|ts|mjs)$/, '');
				if (kind === 'dynamic') { dynamic.push({ from: id, to: target }); continue; }
				queue.push(target);
			} else if (BUILTINS.has(spec) || BUILTINS.has(spec.split('/')[0])) {
				builtins.add(spec.replace(/^node:/, ''));
			} else if (spec === 'vscode') {
				// the API type module itself; resolved at runtime by the require interceptor
			} else {
				const p = npmName(spec);
				if (!npm.has(p)) npm.set(p, { kinds: new Set(), importers: new Set() });
				npm.get(p).kinds.add(kind);
				npm.get(p).importers.add(id);
			}
		}
	}
	for (const [k, v] of files) if (v === null) files.delete(k);
	return { files, npm, builtins, dynamic, css, unresolved, vendored, declOnly, typeOnlyEdges: typeOnlyEdges.count };
}

function summarise(entry, r, top) {
	const layers = {};
	for (const [id, f] of r.files) {
		const l = layerOf(id);
		layers[l] ??= { files: 0, loc: 0 };
		layers[l].files++;
		layers[l].loc += f.loc;
	}
	const subsystems = {};
	for (const [name, pats] of Object.entries(SUBSYSTEMS)) {
		const hits = [...r.files.entries()].filter(([id]) => pats.some(p => p.test(id)));
		if (hits.length) subsystems[name] = { files: hits.length, loc: hits.reduce((s, [, f]) => s + f.loc, 0), examples: hits.sort((a, b) => b[1].loc - a[1].loc).slice(0, 5).map(([id]) => id) };
	}
	// second-level breakdown (vs/<layer>/<dir>) for readability
	const dirs = {};
	for (const [id, f] of r.files) {
		const d = id.split('/').slice(0, id.startsWith('vs/workbench/') ? 4 : 3).join('/');
		dirs[d] ??= { files: 0, loc: 0 };
		dirs[d].files++;
		dirs[d].loc += f.loc;
	}
	const totalLoc = [...r.files.values()].reduce((s, f) => s + f.loc, 0);
	const totalBytes = [...r.files.values()].reduce((s, f) => s + f.bytes, 0);
	return {
		entry,
		files: r.files.size,
		loc: totalLoc,
		sourceBytes: totalBytes,
		layers,
		topDirs: Object.fromEntries(Object.entries(dirs).sort((a, b) => b[1].loc - a[1].loc).slice(0, 40)),
		npmDeps: Object.fromEntries([...r.npm.entries()].sort().map(([k, v]) => [k, { kinds: [...v.kinds], importers: [...v.importers].sort() }])),
		nodeBuiltins: [...r.builtins].sort(),
		subsystems,
		dynamicImports: r.dynamic,
		cssImports: [...r.css].sort(),
		unresolved: r.unresolved,
		vendoredThirdPartyJs: r.vendored,
		declarationOnlyModules: r.declOnly,
		typeOnlyImportEdgesSkipped: r.typeOnlyEdges,
		largestFiles: [...r.files.entries()].sort((a, b) => b[1].loc - a[1].loc).slice(0, top).map(([id, f]) => ({ file: `src/${id}.${f.js ? "js" : "ts"}`, loc: f.loc })),
		allFiles: [...r.files.keys()].sort(),
	};
}

function main() {
	const args = parseArgs(process.argv.slice(2));
	if (args.help || !args.vscode) {
		console.log('usage: node exthost-deps.mjs --vscode <clone> [--out file.json] [--entry vs/..]* [--top N] [--churn tagA,tagB,...]* (tags fetched beforehand with git fetch --depth 1 origin tag <t>)');
		process.exit(args.help ? 0 : 2);
	}
	const vscodeDir = path.resolve(args.vscode);
	const srcDir = path.join(vscodeDir, 'src');
	const ts = loadTypeScript(vscodeDir);
	const scan = ts ? (f, t) => scanTs(ts, f, t) : (_f, t) => scanRegex(t);
	let commit = null;
	try {
		const head = fs.readFileSync(path.join(vscodeDir, '.git', 'HEAD'), 'utf8').trim();
		commit = head.startsWith('ref:') ? fs.readFileSync(path.join(vscodeDir, '.git', head.slice(5).trim()), 'utf8').trim() : head;
	} catch { /* not a git checkout */ }
	const pkg = JSON.parse(fs.readFileSync(path.join(vscodeDir, 'package.json'), 'utf8'));
	const results = {};
	const union = new Set();
	for (const e of args.entries) {
		const r = analyse(srcDir, e, scan);
		results[e] = summarise(e, r, args.top);
		for (const id of r.files.keys()) union.add(id);
		const s = results[e];
		console.error(`${e}: ${s.files} files, ${s.loc} LOC, npm=[${Object.keys(s.npmDeps).join(', ')}]`);
	}
	const out = {
		generatedBy: 'tools/vsx-audit/exthost-deps.mjs',
		vscode: { version: pkg.version, commit },
		parser: ts ? `typescript@${ts.version}` : 'regex-fallback',
		method: 'static import closure; type-only imports skipped; dynamic import() recorded, not followed; LOC = non-blank lines; closure is an upper bound of bundled code (esbuild elides imports used only as types and tree-shakes)',
		unionFiles: union.size,
		entries: results,
		protocol: ts ? analyseProtocol(ts, vscodeDir) : 'skipped: needs the typescript package',
		upgradeChurn: (args.churn ?? []).map(tags => analyseChurn(ts, vscodeDir, tags)),
	};
	const json = JSON.stringify(out, null, '\t') + '\n';
	if (args.out) {
		fs.mkdirSync(path.dirname(path.resolve(args.out)), { recursive: true });
		fs.writeFileSync(args.out, json);
		console.error(`wrote ${args.out}`);
	} else {
		process.stdout.write(json);
	}
}

main();
