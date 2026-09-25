// extHost.protocol.ts RPC shapes + implementing files, and documented built-in commands.
import { ts, parse, lineOf, propName, strValue, fileStats, cmp, sortBy } from './vscode-util.mjs';
import { parseCommands } from './vscode-docs.mjs';

const PROTOCOL = 'src/vs/workbench/api/common/extHost.protocol.ts';

export function buildExtHostShapes(vs, facts) {
	const sf = parse(vs, PROTOCOL);
	const shapes = new Map();
	const contexts = { MainContext: [], ExtHostContext: [] };
	for (const st of sf.statements) {
		if (ts.isInterfaceDeclaration(st) && /^(MainThread|ExtHost)\w*Shape\d*$/.test(st.name.text)) {
			const methods = [];
			for (const m of st.members) {
				if (!m.name) { continue; }
				const isMethod = ts.isMethodSignature(m) || (ts.isPropertySignature(m) && m.type && ts.isFunctionTypeNode(m.type));
				if (isMethod) { methods.push({ name: propName(m.name), line: lineOf(sf, m) }); }
			}
			shapes.set(st.name.text, {
				name: st.name.text,
				side: st.name.text.startsWith('MainThread') ? 'MainThread' : 'ExtHost',
				line: lineOf(sf, st),
				extends: st.heritageClauses ? st.heritageClauses.flatMap(h => h.types.map(t => t.getText())).sort(cmp) : [],
				methodCount: methods.length,
				methods,
			});
		}
		if (ts.isVariableStatement(st)) {
			for (const d of st.declarationList.declarations) {
				const nm = d.name.getText();
				if ((nm === 'MainContext' || nm === 'ExtHostContext') && d.initializer && ts.isObjectLiteralExpression(d.initializer)) {
					for (const p of d.initializer.properties) {
						if (!ts.isPropertyAssignment(p) || !ts.isCallExpression(p.initializer)) { continue; }
						const call = p.initializer;
						contexts[nm].push({
							key: propName(p.name),
							shape: call.typeArguments && call.typeArguments[0] ? call.typeArguments[0].getText() : null,
							identifier: strValue(call.arguments[0]) ?? null,
							line: lineOf(sf, p),
						});
					}
				}
			}
		}
	}
	const fileInfo = new Map();
	const stat = (f) => { if (!fileInfo.has(f)) { fileInfo.set(f, fileStats(vs, f)); } return fileInfo.get(f); };
	const ctxByShape = new Map();
	for (const side of ['MainContext', 'ExtHostContext']) {
		for (const c of contexts[side]) { if (c.shape) { (ctxByShape.get(c.shape) || ctxByShape.set(c.shape, []).get(c.shape)).push({ side, key: c.key, line: c.line }); } }
	}
	const items = sortBy([...shapes.values()], s => s.name).map(s => {
		const ctx = ctxByShape.get(s.name) || [];
		const impl = [];
		// named customers (main thread) resolved via MainContext key
		for (const c of ctx) {
			if (c.side === 'MainContext') {
				for (const nc of facts.namedCustomers.filter(n => n.ctx === c.key)) { impl.push({ file: nc.file, line: nc.line, className: nc.className, via: '@extHostNamedCustomer' }); }
			}
		}
		for (const im of implementorsOf(facts, s.name)) {
			if (!impl.some(x => x.file === im.file && x.className === im.className)) { impl.push(im); }
		}
		const implFiles = [...new Set(impl.map(i => i.file))].sort(cmp).map(f => stat(f));
		const rpcSets = [];
		for (const c of ctx) { if (c.side === 'ExtHostContext') { rpcSets.push(...facts.rpcSets.filter(r => r.ctx === c.key).map(r => `${r.file}:${r.line}`)); } }
		const o = {
			name: s.name,
			side: s.side,
			line: s.line,
			ref: `${PROTOCOL}:${s.line}`,
			extends: s.extends,
			proxyIds: ctx.map(c => `${c.side}.${c.key}`).sort(cmp),
			methodCount: s.methodCount,
			methods: s.methods,
			implementations: sortBy(impl, i => `${i.file}:${String(i.line).padStart(6, '0')}`),
			implementationFiles: implFiles,
		};
		if (rpcSets.length) { o.rpcProtocolSetRefs = rpcSets.sort(cmp); }
		return o;
	});
	const main = items.filter(i => i.side === 'MainThread');
	const ext = items.filter(i => i.side === 'ExtHost');
	const sum = (arr) => arr.reduce((a, f) => ({ lines: a.lines + f.lines, bytes: a.bytes + f.bytes }), { lines: 0, bytes: 0 });
	const uniqFiles = (arr) => { const m = new Map(); for (const i of arr) { for (const f of i.implementationFiles) { m.set(f.path, f); } } return [...m.values()]; };
	return {
		items,
		proxyIdentifiers: {
			MainContext: sortBy(contexts.MainContext, c => c.key),
			ExtHostContext: sortBy(contexts.ExtHostContext, c => c.key),
		},
		summary: {
			protocolFile: fileStats(vs, PROTOCOL),
			mainThreadShapes: main.length,
			extHostShapes: ext.length,
			mainThreadMethods: main.reduce((a, i) => a + i.methodCount, 0),
			extHostMethods: ext.reduce((a, i) => a + i.methodCount, 0),
			mainContextIds: contexts.MainContext.length,
			extHostContextIds: contexts.ExtHostContext.length,
			mainThreadShapesWithoutImpl: main.filter(i => !i.implementations.length).map(i => i.name),
			extHostShapesWithoutImpl: ext.filter(i => !i.implementations.length).map(i => i.name),
			shapesWithoutProxyId: items.filter(i => !i.proxyIds.length).map(i => i.name),
			mainThreadImplFilesTotal: sum(uniqFiles(main)),
			extHostImplFilesTotal: sum(uniqFiles(ext)),
		},
	};
}

/** Classes that (transitively, through interfaces or base classes) extend/implement `shape`. */
function implementorsOf(facts, shape) {
	const out = [];
	const seen = new Set([shape]);
	let frontier = [{ name: shape, path: [] }];
	while (frontier.length) {
		const next = [];
		for (const f of frontier) {
			for (const h of facts.heritage) {
				if (!h.bases.includes(f.name) || seen.has(`${h.name}@${h.file}`)) { continue; }
				seen.add(`${h.name}@${h.file}`);
				const path = [...f.path, h.name];
				if (h.kind === 'class') {
					out.push({ file: h.file, line: h.line, className: h.name, via: path.length === 1 ? 'implements/extends' : `via ${path.slice(0, -1).join(' > ')}` });
				}
				next.push({ name: h.name, path });
			}
		}
		frontier = next;
	}
	return out;
}

export function buildBuiltinCommands(vs, facts, docCmd) {
	const apiRel = 'src/vs/workbench/api/common/extHostApiCommands.ts';
	const docs = parseCommands(docCmd);
	const items = sortBy(docs, d => d.id).map(d => {
		const defs = facts.commandDefs.get(d.id) || [];
		const apiDef = defs.find(x => x.ref.startsWith(apiRel + ':'));
		const o = { id: d.id, description: d.description, section: d.section, docsMdLine: d.mdLine, args: d.args };
		o.inExtHostApiCommands = !!apiDef;
		if (apiDef) { o.extHostApiCommandsRef = apiDef.ref; if (apiDef.internalId) { o.internalCommand = apiDef.internalId; } }
		o.definitionRefs = sortBy(defs, x => x.ref).slice(0, 5).map(x => `${x.ref} (${x.how})`);
		const lits = facts.strings.get(d.id) || [];
		o.literalRefCount = facts.strCount.get(d.id) || 0;
		o.literalRefs = [...lits].sort(cmp).slice(0, 5);
		return o;
	});
	return {
		items,
		summary: {
			documented: items.length,
			inExtHostApiCommands: items.filter(i => i.inExtHostApiCommands).length,
			withDefinitionRef: items.filter(i => i.definitionRefs.length).length,
			noSourceRef: items.filter(i => !i.definitionRefs.length && !i.literalRefCount).map(i => i.id),
			apiCommandsInSourceNotDocumented: [...facts.commandDefs.entries()].filter(([id, defs]) => defs.some(x => x.how === 'ApiCommand') && !docs.some(d => d.id === id)).map(([id]) => id).sort(cmp),
		},
	};
}
