// deps-churn.mjs - per-release churn of VS Code's extension API surface (used by ../exthost-deps.mjs).
//
// For consecutive tags (fetched shallowly beforehand: `git -C <clone> fetch --depth 1 origin tag <t>`)
// it reports `git diff --numstat` totals for the paths a vendored ext host (route 2) or a hand-written
// shim (route 1) must track, and the stable API declarations added/removed in src/vscode-dts/vscode.d.ts
// (qualified names such as `window.showInformationMessage`, `TextEditor.selections`, `ExtensionMode.Test`).
// Diffing two shallow tag commits works because only the two trees are needed, not the history.

import { execFileSync } from 'node:child_process';

export const CHURN_PATHS = {
	'vscode.d.ts': ['src/vscode-dts/vscode.d.ts'],
	'vscode.proposed.*.d.ts': ['src/vscode-dts/vscode.proposed.*.d.ts'],
	'extHost.protocol.ts': ['src/vs/workbench/api/common/extHost.protocol.ts'],
	'workbench/api/**': ['src/vs/workbench/api/'],
	'api/common+node (non-test)': ['src/vs/workbench/api/common/', 'src/vs/workbench/api/node/', ':(exclude)src/vs/workbench/api/test/'],
	'api/browser (main-thread impls)': ['src/vs/workbench/api/browser/'],
	'services/extensions/**': ['src/vs/workbench/services/extensions/'],
};

function git(dir, args) {
	return execFileSync('git', ['-C', dir, ...args], { encoding: 'utf8', maxBuffer: 256 * 1024 * 1024 });
}

function numstat(dir, a, b, paths) {
	const out = git(dir, ['diff', '--numstat', a, b, '--', ...paths]);
	let files = 0, add = 0, del = 0;
	for (const line of out.split('\n').filter(Boolean)) {
		const [x, y] = line.split('\t');
		files++;
		add += x === '-' ? 0 : Number(x);
		del += y === '-' ? 0 : Number(y);
	}
	return { files, add, del };
}

// Qualified declaration names in `declare module 'vscode' { ... }`.
export function apiNames(ts, text) {
	const sf = ts.createSourceFile('vscode.d.ts', text, ts.ScriptTarget.Latest, false, ts.ScriptKind.TS);
	const names = new Set();
	const nameOf = (n) => n.name ? (ts.isIdentifier(n.name) || ts.isStringLiteral(n.name) ? n.name.text : n.name.getText?.(sf) ?? null) : null;
	const walk = (node, prefix) => {
		for (const st of node.statements ?? node.body?.statements ?? []) {
			if (ts.isModuleDeclaration(st)) {
				const nm = nameOf(st);
				const inner = nm === 'vscode' && !prefix ? '' : (prefix ? `${prefix}.${nm}` : nm);
				if (inner) names.add(inner);
				if (st.body) walk(st.body, inner);
				continue;
			}
			if (ts.isVariableStatement(st)) {
				for (const d of st.declarationList.declarations) names.add(prefix ? `${prefix}.${d.name.getText(sf)}` : d.name.getText(sf));
				continue;
			}
			const nm = nameOf(st);
			if (!nm) continue;
			const q = prefix ? `${prefix}.${nm}` : nm;
			names.add(q);
			if (ts.isInterfaceDeclaration(st) || ts.isClassDeclaration(st) || ts.isEnumDeclaration(st)) {
				for (const m of st.members) {
					if (ts.isConstructorDeclaration?.(m)) { names.add(`${q}.constructor`); continue; }
					const mn = m.name ? m.name.getText(sf) : null;
					if (mn) names.add(`${q}.${mn}`);
				}
			}
		}
	};
	walk(sf, '');
	return names;
}

export function analyseChurn(ts, vscodeDir, tags) {
	const dates = {};
	for (const t of tags) dates[t] = git(vscodeDir, ['log', '-1', '--format=%cs', t]).trim();
	const apiAt = {};
	for (const t of tags) apiAt[t] = ts ? apiNames(ts, git(vscodeDir, ['show', `${t}:src/vscode-dts/vscode.d.ts`])) : null;
	const proposedAt = {};
	for (const t of tags) proposedAt[t] = git(vscodeDir, ['ls-tree', '--name-only', t, 'src/vscode-dts/']).split('\n').filter(f => /vscode\.proposed\..*\.d\.ts$/.test(f)).length;
	const steps = [];
	for (let i = 1; i < tags.length; i++) {
		const a = tags[i - 1], b = tags[i];
		const step = { from: a, to: b, fromDate: dates[a], toDate: dates[b], days: Math.round((Date.parse(dates[b]) - Date.parse(dates[a])) / 864e5), paths: {} };
		for (const [k, p] of Object.entries(CHURN_PATHS)) step.paths[k] = numstat(vscodeDir, a, b, p);
		if (ts) {
			const added = [...apiAt[b]].filter(n => !apiAt[a].has(n)).sort();
			const removed = [...apiAt[a]].filter(n => !apiAt[b].has(n)).sort();
			step.stableApi = { declsFrom: apiAt[a].size, declsTo: apiAt[b].size, added: added.length, removed: removed.length, addedNames: added, removedNames: removed };
		}
		step.proposedFiles = { from: proposedAt[a], to: proposedAt[b] };
		steps.push(step);
	}
	return { tags, dates, steps };
}
