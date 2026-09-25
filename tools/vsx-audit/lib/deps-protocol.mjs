// deps-protocol.mjs - measures VS Code's ext host <-> main thread RPC surface from
// src/vs/workbench/api/common/extHost.protocol.ts (used by ../exthost-deps.mjs).
//
// For every proxy identifier in `MainContext` (implemented by the main side) and `ExtHostContext`
// (implemented by the ext host, called by the main side) it resolves the shape interface and counts
// its `$`-prefixed methods, including methods inherited from other interfaces in the same file.
// Shapes are bucketed by easyIDE scope (see SCOPE below; ids from the audit's SURFACES.md).

import fs from 'node:fs';
import path from 'node:path';

// Order matters: first match wins. Buckets:
//  out      = out-of-scope tracks (debug.dap, notebook.api, chat.lm incl. mcp/ai/speech, agent sessions)
//  remote   = remote/tunnel/web-embedder plumbing that a local guest host never needs
//  in       = everything else (core API used by in-scope capabilities)
export const SCOPE = [
	['out', /Debug/],
	['out', /Notebook|Interactive/],
	['out', /Chat|LanguageModel|Embedding|CodeMapper|Mcp|AiRelated|AiEmbedding|AiSettings|Speech|AgentEditorComments|McpShape/],
	['out', /DataChannels|Browsers\b|MainThreadBrowsers|ExtHostBrowsers/],
	['remote', /Tunnel|ManagedSockets|BrowserTunnelProxy|RemoteConnection|Share|ProfileContentHandlers|MeteredConnection|Power/],
	['in', /.*/],
];

export function scopeOf(name) {
	for (const [bucket, re] of SCOPE) if (re.test(name)) return bucket;
	return 'in';
}

export function analyseProtocol(ts, vscodeDir) {
	const rel = 'src/vs/workbench/api/common/extHost.protocol.ts';
	const file = path.join(vscodeDir, rel);
	const text = fs.readFileSync(file, 'utf8');
	const sf = ts.createSourceFile(file, text, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS);
	const line = (n) => sf.getLineAndCharacterOfPosition(n.getStart(sf)).line + 1;

	const ifaces = new Map(); // name -> {methods:Set, heritage:[names], line}
	const contexts = {}; // MainContext / ExtHostContext -> [{sid, type, line}]
	for (const st of sf.statements) {
		if (ts.isInterfaceDeclaration(st)) {
			const methods = new Set();
			for (const m of st.members) {
				const n = m.name && (ts.isIdentifier(m.name) || ts.isStringLiteral(m.name)) ? m.name.text : null;
				if (n && n.startsWith('$') && (ts.isMethodSignature(m) || ts.isPropertySignature(m))) methods.add(n);
			}
			const heritage = (st.heritageClauses || []).flatMap(h => h.types.map(t => t.expression.getText(sf)));
			ifaces.set(st.name.text, { methods, heritage, line: line(st) });
		}
		if (ts.isVariableStatement(st)) {
			for (const d of st.declarationList.declarations) {
				if (!ts.isIdentifier(d.name) || !['MainContext', 'ExtHostContext'].includes(d.name.text)) continue;
				if (!d.initializer || !ts.isObjectLiteralExpression(d.initializer)) continue;
				contexts[d.name.text] = { line: line(d), entries: [] };
				for (const p of d.initializer.properties) {
					if (!ts.isPropertyAssignment(p) || !ts.isCallExpression(p.initializer)) continue;
					const call = p.initializer;
					const type = call.typeArguments?.[0]?.getText(sf) ?? '?';
					const sid = call.arguments[0] && ts.isStringLiteral(call.arguments[0]) ? call.arguments[0].text : p.name.getText(sf);
					contexts[d.name.text].entries.push({ key: p.name.getText(sf), sid, type, line: line(p) });
				}
			}
		}
	}
	const allMethods = (name, seen = new Set()) => {
		if (seen.has(name) || !ifaces.has(name)) return new Set();
		seen.add(name);
		const i = ifaces.get(name);
		const s = new Set(i.methods);
		for (const h of i.heritage) for (const m of allMethods(h, seen)) s.add(m);
		return s;
	};
	const summarise = (ctx) => {
		const rows = (contexts[ctx]?.entries ?? []).map((e, idx) => {
			const methods = allMethods(e.type.replace(/<.*$/, ''));
			return { rpcId: idx + (ctx === 'ExtHostContext' ? (contexts.MainContext?.entries.length ?? 0) + 1 : 1), sid: e.sid, shape: e.type, methods: methods.size, scope: scopeOf(e.sid + ' ' + e.type), line: e.line };
		});
		const byScope = {};
		for (const r of rows) {
			byScope[r.scope] ??= { shapes: 0, methods: 0 };
			byScope[r.scope].shapes++;
			byScope[r.scope].methods += r.methods;
		}
		return { declaredAt: `${rel}:${contexts[ctx]?.line}`, shapes: rows.length, methods: rows.reduce((s, r) => s + r.methods, 0), byScope, rows };
	};
	return {
		file: rel,
		lines: text.split('\n').length,
		note: 'rpcId = 1-based creation order of createProxyIdentifier() across MainContext then ExtHostContext (proxyIdentifier.ts: nid = ++count); both sides must agree, so ids shift whenever an identifier is added or removed upstream',
		mainThread: summarise('MainContext'),
		extHost: summarise('ExtHostContext'),
	};
}
