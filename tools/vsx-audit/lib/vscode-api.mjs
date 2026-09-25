// vscode.d.ts / proposed API extraction via the TypeScript compiler API.
import fs from 'node:fs';
import path from 'node:path';
import { ts, parse, lineOf, jsDocText, firstSentence, hasDeprecatedTag, propName, fileStats, strValue, prop, cmp, sortBy, getCI } from './vscode-util.mjs';

function declKind(n) {
	if (ts.isClassDeclaration(n)) { return 'class'; }
	if (ts.isInterfaceDeclaration(n)) { return 'interface'; }
	if (ts.isEnumDeclaration(n)) { return 'enum'; }
	if (ts.isTypeAliasDeclaration(n)) { return 'type'; }
	if (ts.isFunctionDeclaration(n)) { return 'function'; }
	if (ts.isModuleDeclaration(n)) { return 'namespace'; }
	return undefined;
}

function memberNames(n) {
	const names = [];
	const seen = new Set();
	for (const m of n.members) {
		let name;
		if (ts.isConstructorDeclaration(m) || ts.isConstructSignatureDeclaration(m)) { name = 'constructor'; }
		else if (m.name) { name = propName(m.name); }
		else { continue; } // index / call signatures
		if (!seen.has(name)) { seen.add(name); names.push(name); }
	}
	return names;
}

function isEventType(typeNode) {
	return !!typeNode && ts.isTypeReferenceNode(typeNode) && typeNode.typeName.getText() === 'Event';
}

/**
 * Collect declarations inside a module block. `ns` = namespace path ('' for top-level).
 * Overloaded functions and merged declarations are grouped by id.
 */
function collect(sf, body, ns, byId) {
	for (const st of body.statements) {
		const add = (name, kind, node, extra = {}) => {
			const id = ns ? `${ns}.${name}` : name;
			let it = byId.get(id);
			if (!it) {
				it = { id, kind, namespace: ns || null, name, line: lineOf(sf, node), deprecated: false, overloads: 0, _dep: 0 };
				byId.set(id, it);
			} else if (it.kind !== kind) {
				// declaration merging (e.g. interface + namespace); keep first, record the other kind.
				it.mergedKinds = [...new Set([...(it.mergedKinds || []), kind])].sort(cmp);
			}
			it.overloads++;
			if (hasDeprecatedTag(node) || (ts.isVariableStatement(st) && hasDeprecatedTag(st))) { it._dep++; }
			if (it.docFirstSentence === undefined) {
				const d = firstSentence(jsDocText(node) ?? jsDocText(st));
				if (d) { it.docFirstSentence = d; }
			}
			Object.assign(it, extra);
			return it;
		};
		if (ts.isVariableStatement(st)) {
			const isConst = (st.declarationList.flags & ts.NodeFlags.Const) !== 0;
			for (const d of st.declarationList.declarations) {
				const name = d.name.getText();
				let kind = ns ? (isEventType(d.type) ? 'namespace-event' : isConst ? 'namespace-const' : 'namespace-variable') : 'variable';
				add(name, kind, d);
			}
			continue;
		}
		const k = declKind(st);
		if (!k || !st.name) { continue; }
		const name = st.name.getText();
		if (k === 'namespace') {
			const id = ns ? `${ns}.${name}` : name;
			const inner = st.body && ts.isModuleBlock(st.body) ? st.body : undefined;
			const it = add(name, ns ? 'namespace-namespace' : 'namespace', st);
			if (inner) {
				const before = new Set(byId.keys());
				collect(sf, inner, id, byId);
				const direct = [...byId.keys()].filter(x => !before.has(x) && byId.get(x).namespace === id);
				it.memberCount = direct.length;
			}
			continue;
		}
		const kind = ns ? (k === 'function' ? 'namespace-function' : `namespace-${k}`) : k;
		const extra = {};
		if (k === 'class' || k === 'interface' || k === 'enum') {
			extra.members = memberNames(st);
			extra.memberCount = extra.members.length;
		}
		add(name, kind, st, extra);
	}
}

export function extractApi(vs) {
	const rel = 'src/vscode-dts/vscode.d.ts';
	const sf = parse(vs, rel);
	const mod = sf.statements.find(s => ts.isModuleDeclaration(s) && strValue(s.name) === 'vscode');
	if (!mod) { throw new Error("declare module 'vscode' not found"); }
	const byId = new Map();
	collect(sf, mod.body, '', byId);
	const items = sortBy([...byId.values()], x => x.id).map(it => {
		it.deprecated = it._dep > 0 && it._dep === it.overloads;
		if (it._dep > 0 && it._dep !== it.overloads) { it.deprecatedOverloads = it._dep; }
		delete it._dep;
		const o = { id: it.id, kind: it.kind, namespace: it.namespace, name: it.name, line: it.line, deprecated: it.deprecated };
		if (it.deprecatedOverloads) { o.deprecatedOverloads = it.deprecatedOverloads; }
		if (it.mergedKinds) { o.mergedKinds = it.mergedKinds; }
		if (it.memberCount !== undefined) { o.memberCount = it.memberCount; }
		if (it.members) { o.members = it.members; }
		o.overloads = it.overloads;
		if (it.docFirstSentence) { o.docFirstSentence = it.docFirstSentence; }
		return o;
	});
	const countsByKind = {};
	for (const it of items) { countsByKind[it.kind] = (countsByKind[it.kind] || 0) + 1; }
	const namespaces = items.filter(i => i.kind === 'namespace').map(i => i.id);
	const countsByNamespace = {};
	for (const it of items) { if (it.namespace) { countsByNamespace[it.namespace] = (countsByNamespace[it.namespace] || 0) + 1; } }
	const nsMembers = items.filter(i => i.namespace).length;
	const nsMembersNonDeprecated = items.filter(i => i.namespace && !i.deprecated).length;
	const summary = {
		file: fileStats(vs, rel),
		countsByKind: sortObj(countsByKind),
		namespaces,
		countsByNamespace: sortObj(countsByNamespace),
		namespaceMembersTotal: nsMembers,
		namespaceMembersNonDeprecated: nsMembersNonDeprecated,
		deprecatedTotal: items.filter(i => i.deprecated).length,
		countingRules: [
			'One item per top-level declaration inside declare module \'vscode\'; overloads and merged declarations share one id (overloads = number of declarations).',
			'Namespace members: one item per exported function name (overloads grouped), const/let (const typed Event<T> => namespace-event), or nested declaration.',
			'Class/interface/enum memberCount = unique member names (constructor counted once, index/call signatures excluded).',
			'deprecated = every declaration of the id carries @deprecated; partial deprecation is reported as deprecatedOverloads.',
		],
	};
	return { summary, items };
}

function sortObj(o) { return Object.fromEntries(Object.entries(o).sort((a, b) => cmp(a[0], b[0]))); }

/** Proposed API d.ts list, joined with allApiProposals (versions) and optional product.json enabled proposals. */
export function extractProposed(vs, products, extensionIds) {
	const dir = 'src/vscode-dts';
	const files = fs.readdirSync(path.join(vs, dir)).filter(f => /^vscode\.proposed\..+\.d\.ts$/.test(f)).sort(cmp);
	// allApiProposals registry (generated file) for versions.
	const regRel = 'src/vs/platform/extensions/common/extensionsApiProposals.ts';
	const reg = new Map();
	if (fs.existsSync(path.join(vs, regRel))) {
		const sf = parse(vs, regRel);
		const visit = (n) => {
			if (ts.isVariableDeclaration(n) && n.name.getText() === '_allApiProposals' && n.initializer && ts.isObjectLiteralExpression(n.initializer)) {
				for (const p of n.initializer.properties) {
					if (!ts.isPropertyAssignment(p)) { continue; }
					const v = prop(p.initializer, 'version');
					reg.set(propName(p.name), { line: lineOf(sf, p), version: v && ts.isNumericLiteral(v) ? Number(v.text) : undefined });
				}
			}
			ts.forEachChild(n, visit);
		};
		visit(sf);
	}
	const items = files.map(f => {
		const name = f.replace(/^vscode\.proposed\./, '').replace(/\.d\.ts$/, '');
		const st = fileStats(vs, `${dir}/${f}`);
		const r = reg.get(name);
		const o = { name, file: st.path, lines: st.lines, bytes: st.bytes, inRegistry: !!r };
		if (r && r.version !== undefined) { o.version = r.version; }
		return o;
	});
	const regOnly = [...reg.keys()].filter(k => !files.includes(`vscode.proposed.${k}.d.ts`)).sort(cmp);
	const productData = products.map(({ label, file }) => {
		const p = JSON.parse(fs.readFileSync(file, 'utf8'));
		const eap = p.extensionEnabledApiProposals;
		const res = { label, file, productVersion: p.version ?? null, productCommit: p.commit ?? null, productDate: p.date ?? null, quality: p.quality ?? null, hasExtensionEnabledApiProposals: !!eap };
		if (eap) {
			res.totalEntries = Object.keys(eap).length;
			res.entries = {};
			for (const id of extensionIds) {
				const hit = getCI(eap, id);
				res.entries[id] = hit ? { key: hit.key, proposals: [...hit.value].sort(cmp) } : null;
			}
		}
		for (const k of ['extensionAllowedProposedApi', 'extensionsEnabledWithApiProposalVersion']) {
			if (Array.isArray(p[k])) { res[k + 'Count'] = p[k].length; }
		}
		return res;
	});
	return { items, registryRef: regRel, registryCount: reg.size, registryOnly: regOnly, products: productData };
}
