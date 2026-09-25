// Single AST pass over src/vs (non-test .ts) collecting the raw facts the other extractors join.
import { ts, walk, isTestPath, parse, lineOf, strValue, strPrefix, descValue, prop, propName, findVarInit, cmp } from './vscode-util.mjs';

const EXPR_FNS = new Set(['has', 'equals', 'notEquals', 'regex', 'greater', 'greaterEquals', 'smaller', 'smallerEquals', 'in', 'notIn', 'not']);
const STR_INDEX_RE = /^[\w.*:\-\/$@#]{1,120}$/;

export function scanSource(vs) {
	const files = walk(vs, 'src/vs', rel => rel.endsWith('.ts') && !rel.endsWith('.d.ts') && !isTestPath(rel));
	const facts = {
		files,
		extensionPoints: [],     // {point, file, line, sf, obj, generatorYields[], ...}
		activateCalls: [],       // {prefix, dynamic, file, line, raw}
		rawContextKeys: [],      // {name, type, description, file, line, varName}
		exprKeyRefs: new Map(),  // name -> [{file,line,fn}]
		createKeyRefs: new Map(),// name -> [{file,line}]
		strings: new Map(),      // literal text -> [ref...] (first 8) ; count in strCount
		strCount: new Map(),
		templateHeads: new Map(),// template head text -> [ref...]
		commandDefs: new Map(),  // id -> [{file,line,how}]
		proposalChecks: new Map(),// file -> Set(proposal)
		namedCustomers: [],      // {ctx, file, line, className}
		heritage: [],            // {name, kind, file, line, bases[]} for every class/interface with extends/implements
		rpcSets: [],             // {ctx, file, line}
		registerExtPointCalls: [],// {arg, ref}
	};
	const pushRef = (map, key, r, max = 8) => {
		let a = map.get(key);
		if (!a) { a = []; map.set(key, a); }
		if (a.length < max) { a.push(r); }
	};
	const objVars = new Map(); // const name -> [{rel, node}] (object literal initializers)
	const syms = new Map(); // symbol text -> value | null (ambiguous)
	const addSym = (k, v) => { if (!syms.has(k)) { syms.set(k, v); } else if (syms.get(k) !== v) { syms.set(k, null); } };
	const keyName = (node) => {
		if (!node) { return undefined; }
		const v = strValue(node);
		if (v !== undefined) { return v; }
		if (ts.isIdentifier(node) || ts.isPropertyAccessExpression(node)) { return '@@' + node.getText().replace(/\s+/g, ''); }
		return undefined;
	};
	for (const rel of files) {
		const sf = parse(vs, rel);
		const r = (n) => `${rel}:${lineOf(sf, n)}`;
		const visit = (n) => {
			// string literal index
			if (ts.isStringLiteral(n) || ts.isNoSubstitutionTemplateLiteral(n)) {
				const t = n.text;
				if (STR_INDEX_RE.test(t) && !ts.isImportDeclaration(n.parent) && !ts.isExportDeclaration(n.parent) && !ts.isExternalModuleReference(n.parent)) {
					pushRef(facts.strings, t, r(n));
					facts.strCount.set(t, (facts.strCount.get(t) || 0) + 1);
				}
			} else if (ts.isTemplateExpression(n)) {
				if (n.head.text && STR_INDEX_RE.test(n.head.text)) { pushRef(facts.templateHeads, n.head.text, r(n)); }
			}
			// string-valued symbols (enum members, consts, static readonly) for context-key name resolution
			if (ts.isEnumDeclaration(n)) {
				for (const m of n.members) { const v = m.initializer && strValue(m.initializer); if (v !== undefined) { addSym(`${n.name.text}.${propName(m.name)}`, v); } }
			} else if (ts.isVariableDeclaration(n) && ts.isIdentifier(n.name) && n.initializer && (n.parent.flags & ts.NodeFlags.Const)) {
				const v = strValue(n.initializer); if (v !== undefined) { addSym(n.name.text, v); }
				if (ts.isObjectLiteralExpression(n.initializer)) { const a = objVars.get(n.name.text) || []; a.push({ rel, node: n.initializer }); objVars.set(n.name.text, a); }
			} else if (ts.isPropertyDeclaration(n) && n.initializer && n.name && ts.isClassDeclaration(n.parent) && n.parent.name && n.modifiers && n.modifiers.some(x => x.kind === ts.SyntaxKind.StaticKeyword)) {
				const v = strValue(n.initializer); if (v !== undefined) { addSym(`${n.parent.name.text}.${propName(n.name)}`, v); }
			}
			// object literal extension point descriptor
			if (ts.isObjectLiteralExpression(n)) {
				const ep = prop(n, 'extensionPoint');
				const js = prop(n, 'jsonSchema');
				if (ep && js) { facts.extensionPoints.push(describeExtensionPoint(sf, n, ep, js)); }
			}
			if (ts.isNewExpression(n)) {
				const callee = n.expression.getText();
				if (/(^|\.)RawContextKey$/.test(callee) && n.arguments && n.arguments.length) {
					const name = keyName(n.arguments[0]);
					if (name !== undefined) {
						const d = n.arguments[2];
						let description = descValue(d);
						let type = n.typeArguments && n.typeArguments[0] ? n.typeArguments[0].getText().replace(/\s+/g, ' ') : undefined;
						if (d && ts.isObjectLiteralExpression(d)) {
							description = descValue(prop(d, 'description'));
							const t = strValue(prop(d, 'type'));
							if (t && !type) { type = t; }
						}
						let varName;
						let p = n.parent;
						while (p && !ts.isVariableDeclaration(p) && !ts.isPropertyDeclaration(p) && !ts.isPropertyAssignment(p) && !ts.isSourceFile(p)) { p = p.parent; }
						if (p && !ts.isSourceFile(p) && p.name) { varName = propName(p.name); }
						const o = { name, file: rel, line: lineOf(sf, n) };
						if (type) { o.type = type; }
						if (description) { o.description = description; }
						if (varName) { o.varName = varName; }
						facts.rawContextKeys.push(o);
					}
				}
				if (callee === 'ApiCommand' && n.arguments && n.arguments.length >= 2) {
					const id = strValue(n.arguments[0]);
					if (id) { pushRef(facts.commandDefs, id, { ref: r(n), how: 'ApiCommand', internalId: strValue(n.arguments[1]) }); }
				}
			}
			if (ts.isCallExpression(n)) {
				const ex = n.expression;
				const callee = ex.getText();
				const last = ts.isPropertyAccessExpression(ex) ? ex.name.text : ts.isIdentifier(ex) ? ex.text : '';
				const a0 = n.arguments[0];
				if (last === 'registerExtensionPoint' && a0) {
					facts.registerExtPointCalls.push({ arg: ts.isIdentifier(a0) ? a0.text : ts.isObjectLiteralExpression(a0) ? '{...}' : a0.getText().slice(0, 60), ref: r(n) });
				}
				if (/^_?activateByEvent$/.test(last) && a0) {
					const p = strPrefix(a0);
					facts.activateCalls.push({ prefix: p ? p.text : undefined, dynamic: p ? p.dynamic : true, raw: a0.getText().slice(0, 80), file: rel, line: lineOf(sf, n) });
				}
				if (/(^|\.)ContextKeyExpr\.\w+$/.test(callee) && EXPR_FNS.has(last)) {
					const k = keyName(a0);
					if (k) { pushRef(facts.exprKeyRefs, k, { ref: r(n), fn: last }, 5); }
				}
				if (last === 'createKey' && a0) {
					const k = keyName(a0);
					if (k) { pushRef(facts.createKeyRefs, k, { ref: r(n) }, 5); }
				}
				if (last === 'isProposedApiEnabled' || last === 'checkProposedApiEnabled') {
					const pr = strValue(n.arguments[1]);
					if (pr) {
						let s = facts.proposalChecks.get(rel);
						if (!s) { s = new Set(); facts.proposalChecks.set(rel, s); }
						s.add(pr);
					}
				}
				if (last === 'registerCommand' && a0) {
					const id = strValue(a0) ?? (ts.isObjectLiteralExpression(a0) ? strValue(prop(a0, 'id')) : undefined);
					if (id) { pushRef(facts.commandDefs, id, { ref: r(n), how: 'registerCommand' }); }
				}
				if ((last === 'registerAction2' || last === 'registerEditorAction') && a0) { /* ids caught via id: property below */ }
				if (last === 'extHostNamedCustomer' && a0 && ts.isPropertyAccessExpression(a0) && a0.expression.getText() === 'MainContext') {
					let cls = n.parent;
					while (cls && !ts.isClassDeclaration(cls)) { cls = cls.parent; }
					facts.namedCustomers.push({ ctx: a0.name.text, file: rel, line: lineOf(sf, n), className: cls && cls.name ? cls.name.text : undefined });
				}
				if (last === 'set' && a0 && ts.isPropertyAccessExpression(a0) && a0.expression.getText() === 'ExtHostContext') {
					facts.rpcSets.push({ ctx: a0.name.text, file: rel, line: lineOf(sf, n) });
				}
			}
			if (ts.isPropertyAssignment(n) && propName(n.name) === 'id') {
				const id = strValue(n.initializer);
				if (id && /\./.test(id) && ts.isObjectLiteralExpression(n.parent)) {
					pushRef(facts.commandDefs, id, { ref: r(n), how: 'id-property' });
				}
			}
			if ((ts.isClassDeclaration(n) || ts.isInterfaceDeclaration(n)) && n.heritageClauses && n.name) {
				const bases = [];
				for (const h of n.heritageClauses) {
					for (const t of h.types) { bases.push(t.expression.getText().replace(/^.*\./, '')); }
				}
				facts.heritage.push({ name: n.name.text, kind: ts.isClassDeclaration(n) ? 'class' : 'interface', file: rel, line: lineOf(sf, n), bases });
			}
			ts.forEachChild(n, visit);
		};
		visit(sf);
	}
	// resolve cross-file jsonSchema references for extension point descriptions
	for (const ep of facts.extensionPoints) {
		if (ep._schemaName) {
			const cands = objVars.get(ep._schemaName) || [];
			if (cands.length === 1) { ep.description = schemaDescription(cands[0].node); ep.schemaRef += ` (${cands[0].rel})`; }
		}
		delete ep._schemaName;
	}
	// resolve symbolic context-key names
	facts.unresolvedContextKeyNames = new Map();
	const resolve = (k, where) => {
		if (!k.startsWith('@@')) { return k; }
		const sym = k.slice(2);
		const v = syms.get(sym) ?? syms.get(sym.replace(/^.*\.(\w+\.\w+)$/, '$1'));
		if (typeof v === 'string') { return v; }
		facts.unresolvedContextKeyNames.set(sym, (facts.unresolvedContextKeyNames.get(sym) || 0) + 1);
		return undefined;
	};
	facts.rawContextKeys = facts.rawContextKeys.map(k => { const nm = resolve(k.name); return nm === undefined ? undefined : { ...k, name: nm, ...(k.name.startsWith('@@') ? { nameExpr: k.name.slice(2) } : {}) }; }).filter(Boolean);
	// `<RawContextKey var>.key` -> declared name (by variable/property name; ambiguous names skipped)
	const byVar = new Map();
	for (const k of facts.rawContextKeys) { if (k.varName) { byVar.set(k.varName, byVar.has(k.varName) && byVar.get(k.varName) !== k.name ? null : k.name); } }
	for (const key of ['exprKeyRefs', 'createKeyRefs']) {
		const m = new Map();
		for (const [k, refs] of facts[key]) {
			const km = /^@@(?:.*\.)?(\w+)\.key$/.exec(k);
			const nm = km && typeof byVar.get(km[1]) === 'string' ? byVar.get(km[1]) : resolve(k);
			if (nm === undefined) { continue; }
			const a = m.get(nm) || [];
			for (const r of refs) { if (a.length < 5) { a.push(r); } }
			m.set(nm, a);
		}
		facts[key] = m;
	}
	return facts;
}

/** Resolve an extensionPoint value to one or more names (string literal, enum member, or function parameter with literal call sites). */
function resolvePointNames(sf, ep) {
	const lit = strValue(ep);
	if (lit !== undefined) { return [lit]; }
	if (ts.isIdentifier(ep)) {
		// function parameter? find enclosing function and its call sites in this file.
		let fn = ep.parent;
		while (fn && !ts.isFunctionDeclaration(fn) && !ts.isArrowFunction(fn) && !ts.isFunctionExpression(fn)) { fn = fn.parent; }
		if (fn && fn.parameters.some(p => p.name.getText() === ep.text)) {
			const idx = fn.parameters.findIndex(p => p.name.getText() === ep.text);
			const fname = fn.name ? fn.name.getText() : undefined;
			const names = [];
			const visit = (n) => {
				if (ts.isCallExpression(n) && fname && n.expression.getText() === fname && n.arguments[idx]) {
					const v = resolveEnumOrLiteral(sf, n.arguments[idx]);
					if (v !== undefined) { names.push(v); }
				}
				ts.forEachChild(n, visit);
			};
			visit(sf);
			if (names.length) { return names; }
		}
		const init = findVarInit(sf, ep.text);
		const v = init ? strValue(init) : undefined;
		if (v !== undefined) { return [v]; }
	}
	const v = resolveEnumOrLiteral(sf, ep);
	return v !== undefined ? [v] : [];
}

function resolveEnumOrLiteral(sf, node) {
	const lit = strValue(node);
	if (lit !== undefined) { return lit; }
	if (ts.isPropertyAccessExpression(node)) {
		const en = node.expression.getText();
		const mem = node.name.text;
		let found;
		const visit = (n) => {
			if (found !== undefined) { return; }
			if (ts.isEnumDeclaration(n) && n.name.text === en) {
				for (const m of n.members) { if (propName(m.name) === mem) { found = m.initializer ? strValue(m.initializer) : undefined; } }
			}
			ts.forEachChild(n, visit);
		};
		visit(sf);
		return found;
	}
	return undefined;
}

function schemaDescription(schema) {
	return schema && ts.isObjectLiteralExpression(schema) ? (descValue(prop(schema, 'description')) ?? descValue(prop(schema, 'markdownDescription'))) : undefined;
}

function describeExtensionPoint(sf, obj, ep, js) {
	const names = resolvePointNames(sf, ep);
	let schema = js;
	const schemaName = ts.isIdentifier(js) ? js.text : ts.isPropertyAccessExpression(js) ? js.name.text : undefined;
	if (schemaName) { schema = findVarInit(sf, schemaName) ?? js; }
	let description = schemaDescription(schema);
	const dollarRef = ts.isObjectLiteralExpression(schema) ? prop(schema, '$ref') : undefined;
	const gen = prop(obj, 'activationEventsGenerator');
	const yields = new Set();
	if (gen) {
		const visit = (n) => {
			if (ts.isYieldExpression(n) && n.expression) {
				const p = strPrefix(n.expression);
				if (p) { yields.add(p.text); }
			}
			ts.forEachChild(n, visit);
		};
		visit(gen);
	}
	const dek = prop(obj, 'defaultExtensionKind');
	const chr = prop(obj, 'canHandleResolver');
	const parent = obj.parent;
	const direct = ts.isCallExpression(parent) && /registerExtensionPoint$/.test(parent.expression.getText());
	return {
		names,
		registeredDirectly: direct,
		declName: !direct && ts.isVariableDeclaration(parent) ? parent.name.getText() : undefined,
		pointExpr: names.length ? undefined : ep.getText(),
		file: sf.fileName,
		line: lineOf(sf, obj),
		description,
		schemaRef: schemaName ? js.getText() : dollarRef ? `$ref: ${dollarRef.getText()}` : undefined,
		_schemaName: schemaName && !ts.isObjectLiteralExpression(schema) ? schemaName : undefined,
		activationEventsGenerator: !!gen,
		implicitEventPrefixes: [...yields].sort(cmp),
		defaultExtensionKind: dek && ts.isArrayLiteralExpression(dek) ? dek.elements.map(e => strValue(e) ?? e.getText()) : undefined,
		canHandleResolver: chr ? chr.kind === ts.SyntaxKind.TrueKeyword : undefined,
		deps: (() => { const d = prop(obj, 'deps'); return d && ts.isArrayLiteralExpression(d) ? d.elements.map(e => e.getText()) : undefined; })(),
	};
}
