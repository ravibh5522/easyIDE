// when-clause context keys (docs + source) and the when-clause operator grammar.
import { ts, parse, lineOf, strValue, propName, cmp, sortBy } from './vscode-util.mjs';
import { parseContextKeys } from './vscode-docs.mjs';

export function buildContextKeys(vs, facts, docWC) {
	const docs = parseContextKeys(docWC);
	const map = new Map();
	const get = (name) => {
		let e = map.get(name);
		if (!e) { e = { name, documented: false, sources: new Set(), decls: [] }; map.set(name, e); }
		return e;
	};
	for (const d of docs) {
		const e = get(d.name);
		e.documented = true;
		e.doc = d;
		e.sources.add('docs');
	}
	for (const k of facts.rawContextKeys) {
		const e = get(k.name);
		e.sources.add('RawContextKey');
		e.decls.push(k);
	}
	for (const [k, refs] of facts.createKeyRefs) { const e = get(k); e.sources.add('createKey'); e.createKeyRef = refs[0].ref; }
	for (const [k, refs] of facts.exprKeyRefs) {
		if (!map.has(k) && !/^[\w.:\-\/]+$/.test(k)) { continue; }
		const e = get(k);
		e.sources.add('ContextKeyExpr');
		e.exprRef = `${refs[0].ref} (${refs[0].fn})`;
	}
	const items = sortBy([...map.values()], e => e.name).map(e => {
		const decls = sortBy(e.decls, d => `${d.file}:${String(d.line).padStart(6, '0')}`);
		const o = { name: e.name, documented: e.documented };
		if (e.doc) {
			o.docDescription = e.doc.description;
			o.docSection = e.doc.category ? `${e.doc.section} / ${e.doc.category}` : e.doc.section;
			if (/\$\{|^config\./.test(e.name)) { o.docPattern = true; }
		}
		o.sources = [...e.sources].sort(cmp);
		const typed = decls.find(d => d.type);
		const described = decls.find(d => d.description);
		if (typed) { o.type = typed.type; }
		if (described) { o.description = described.description; }
		if (decls.length) {
			o.declarationCount = decls.length;
			o.declarations = decls.slice(0, 10).map(d => ({ ref: `${d.file}:${d.line}`, ...(d.varName ? { varName: d.varName } : {}), ...(d.nameExpr ? { nameExpr: d.nameExpr } : {}), ...(d.type ? { type: d.type } : {}) }));
		}
		if (e.createKeyRef) { o.createKeyRef = e.createKeyRef; }
		if (e.exprRef) { o.exprRef = e.exprRef; }
		return o;
	});
	const docsNotInSource = items.filter(i => i.documented && i.sources.length === 1).map(i => i.name);
	return {
		items,
		operators: extractOperators(vs),
		summary: {
			total: items.length,
			documented: items.filter(i => i.documented).length,
			rawContextKeyNames: items.filter(i => i.sources.includes('RawContextKey')).length,
			rawContextKeyDeclarations: facts.rawContextKeys.length,
			createKeyOnly: items.filter(i => i.sources.length === 1 && i.sources[0] === 'createKey').length,
			exprOnly: items.filter(i => i.sources.length === 1 && i.sources[0] === 'ContextKeyExpr').length,
			documentedNotFoundInSource: docsNotInSource,
			unresolvedSymbolicKeyNames: facts.unresolvedContextKeyNames.size,
			unresolvedSymbolicKeyNameSamples: [...facts.unresolvedContextKeyNames.keys()].sort(cmp).slice(0, 25),
			countingRules: [
				'One item per distinct key name. Sources: RawContextKey (new RawContextKey(name,...)), createKey (contextKeyService.createKey(name,...)), ContextKeyExpr (ContextKeyExpr.has/equals/...(name)), docs.',
				'Names given as enum members / string consts / static readonly fields are resolved across src/vs; ambiguous or dynamic names are counted in unresolvedSymbolicKeyNames.',
				'Keys built at runtime (e.g. view.<id>.visible, config.*, per-extension setContext keys) are not enumerable from source.',
			],
		},
	};
}

/** Operators: lexemes from Scanner.getLexeme + keyword map (scanner.ts), parser usage lines (contextkey.ts). */
function extractOperators(vs) {
	const scanRel = 'src/vs/platform/contextkey/common/scanner.ts';
	const ckRel = 'src/vs/platform/contextkey/common/contextkey.ts';
	const sf = parse(vs, scanRel);
	const lex = new Map(); // TokenType -> {lexemes, line}
	const visit = (n) => {
		if (ts.isMethodDeclaration(n) && n.name.getText() === 'getLexeme' && n.body) {
			const sw = n.body.statements.find(ts.isSwitchStatement);
			let pending = [];
			for (const c of sw.caseBlock.clauses) {
				if (ts.isCaseClause(c)) { pending.push({ tt: c.expression.getText().replace(/^TokenType\./, ''), line: lineOf(sf, c) }); }
				const ret = c.statements.find(ts.isReturnStatement);
				if (ret) {
					const vals = [];
					const collect = (e) => {
						const s = strValue(e);
						if (s !== undefined) { vals.push(s); return; }
						if (ts.isConditionalExpression(e)) { collect(e.whenTrue); collect(e.whenFalse); return; }
						if (ts.isParenthesizedExpression(e)) { collect(e.expression); }
					};
					collect(ret.expression);
					for (const p of pending) { lex.set(p.tt, { lexemes: vals, line: p.line }); }
					pending = [];
				}
			}
		}
		ts.forEachChild(n, visit);
	};
	visit(sf);
	const ck = parse(vs, ckRel);
	const lines = ck.text.split('\n');
	const parserStart = lines.findIndex(l => /^export class Parser\b/.test(l));
	const firstUse = (tt) => {
		for (let i = Math.max(0, parserStart); i < lines.length; i++) {
			if (new RegExp(`TokenType\\.${tt}\\b`).test(lines[i])) { return `${ckRel}:${i + 1}`; }
		}
		return null;
	};
	const meaning = {
		LParen: 'group open', RParen: 'group close', Neg: 'logical not', Eq: 'equality', NotEq: 'inequality', Lt: 'less than (numeric)', LtEq: 'less or equal (numeric)',
		Gt: 'greater than (numeric)', GtEq: 'greater or equal (numeric)', RegexOp: 'regex match (rhs /re/flags)', In: 'membership (key in arrayOrObjectKey)', Not: '`not in` (only valid before in)',
		And: 'logical and', Or: 'logical or', True: 'literal true', False: 'literal false',
	};
	const ops = [];
	for (const [tt, v] of lex) {
		if (!meaning[tt]) { continue; } // skip Str/QuotedStr/RegexStr/Error/EOF
		ops.push({ tokenType: tt, lexemes: v.lexemes, meaning: meaning[tt], scannerRef: `${scanRel}:${v.line}`, parserRef: firstUse(tt) });
	}
	// composite `not in`
	ops.push({ tokenType: 'Not+In', lexemes: ['not in'], meaning: 'negated membership', scannerRef: ops.find(o => o.tokenType === 'Not')?.scannerRef ?? null, parserRef: firstUse('Not') });
	// ContextKeyExprType enum (evaluation node kinds)
	const types = [];
	const v2 = (n) => {
		if (ts.isEnumDeclaration(n) && n.name.text === 'ContextKeyExprType') {
			for (const m of n.members) { types.push({ name: propName(m.name), ref: `${ckRel}:${lineOf(ck, m)}` }); }
		}
		ts.forEachChild(n, v2);
	};
	v2(ck);
	return { operators: sortBy(ops, o => o.tokenType), exprTypes: types, parserClassRef: parserStart >= 0 ? `${ckRel}:${parserStart + 1}` : null };
}
