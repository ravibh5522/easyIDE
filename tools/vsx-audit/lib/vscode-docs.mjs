// Fetch + parse the code.visualstudio.com API reference pages (raw markdown variants).
// Each page is available as markdown at https://code.visualstudio.com/raw/api/references/<page>.md
// (linked from the HTML page's "View as Markdown" action).
import fs from 'node:fs';
import path from 'node:path';
import { sha256, cmp } from './vscode-util.mjs';

export const DOC_PAGES = ['contribution-points', 'activation-events', 'when-clause-contexts', 'commands'];
export const docUrl = (page) => `https://code.visualstudio.com/api/references/${page}`;
const rawUrl = (page) => `https://code.visualstudio.com/raw/api/references/${page}.md`;

/** Load a docs page: from cacheDir if present (and !refresh), else fetch (and store in cacheDir if given). */
export async function loadDoc(page, cacheDir, refresh) {
	const cached = cacheDir ? path.join(cacheDir, `${page}.md`) : undefined;
	let text;
	if (cached && !refresh && fs.existsSync(cached)) {
		text = fs.readFileSync(cached, 'utf8');
	} else {
		const res = await fetch(rawUrl(page));
		if (!res.ok) { throw new Error(`fetch ${rawUrl(page)}: HTTP ${res.status}`); }
		text = await res.text();
		if (cached) { fs.mkdirSync(cacheDir, { recursive: true }); fs.writeFileSync(cached, text); }
	}
	return { page, url: docUrl(page), rawUrl: rawUrl(page), sha256: sha256(text), text, lines: text.split('\n') };
}

export function docMeta(d) { return { url: d.url, rawUrl: d.rawUrl, sha256: d.sha256 }; }

/** `## contributes.X` headings. */
export function parseContributionPoints(d) {
	const out = [];
	d.lines.forEach((l, i) => {
		const m = /^## contributes\.(\S+)/.exec(l);
		if (m) { out.push({ point: m[1], mdLine: i + 1 }); }
	});
	return out;
}

/** Menu keys mentioned (backticked, containing '/' or known single words) inside the contributes.menus section. */
export function parseMenuSection(d) {
	const start = d.lines.findIndex(l => /^## contributes\.menus/.test(l));
	if (start < 0) { return new Set(); }
	let end = d.lines.findIndex((l, i) => i > start && /^## /.test(l));
	if (end < 0) { end = d.lines.length; }
	const keys = new Set();
	for (const l of d.lines.slice(start, end)) {
		for (const m of l.matchAll(/`([A-Za-z][\w\/.-]*)`/g)) { keys.add(m[1]); }
	}
	return keys;
}

/** Activation events: bullet list at the top + `## x` / `### x` sections. */
export function parseActivationEvents(d) {
	const out = new Map();
	d.lines.forEach((l, i) => {
		let m = /^\s*- \[`([^`]+)`\]/.exec(l);
		if (m && !out.has(m[1])) { out.set(m[1], { name: m[1], mdLine: i + 1 }); }
		m = /^##+ (on\w+|workspaceContains)\s*$/.exec(l);
		if (m && !out.has(m[1])) { out.set(m[1], { name: m[1], mdLine: i + 1 }); }
	});
	// Does the docs example use "name:arg"?
	for (const ev of out.values()) {
		ev.docTakesArgument = ev.name !== '*' && d.text.includes(`"${ev.name}:`);
	}
	return [...out.values()];
}

/** when-clause context tables: rows "`key` | description". */
export function parseContextKeys(d) {
	const out = [];
	let section = '';
	let h2 = '';
	let category = '';
	d.lines.forEach((l, i) => {
		const h = /^(##+) (.+)$/.exec(l);
		if (h) { section = h[2].trim(); if (h[1] === '##') { h2 = section; } category = ''; return; }
		if (/operator/i.test(h2)) { return; } // operator tables, not context keys
		const cat = /^\*\*(.+?)\*\*\s*\|/.exec(l);
		if (cat) { category = cat[1]; return; }
		const m = /^`([^`]+)`\s*\|\s*(.*)$/.exec(l);
		if (m) {
			out.push({ name: m[1], description: m[2].replace(/<br>/g, ' ').replace(/\s+/g, ' ').trim(), section, category: category || undefined, mdLine: i + 1 });
		}
	});
	return out;
}

/** Built-in commands: "`id` - description" lines followed by "* _arg_ - text" lists. */
export function parseCommands(d) {
	const out = [];
	let section = '';
	let cur;
	d.lines.forEach((l, i) => {
		const h = /^## (.+)$/.exec(l);
		if (h) { section = h[1].trim(); cur = undefined; return; }
		const m = /^`([^`]+)`\s+-\s*(.*)$/.exec(l);
		if (m) { cur = { id: m[1], description: m[2].trim(), section, mdLine: i + 1, args: [] }; out.push(cur); return; }
		const a = /^\s*[*-]\s+_([^_]+)_\s*-\s*(.*)$/.exec(l);
		if (a && cur) { cur.args.push({ name: a[1], description: a[2].trim() }); }
	});
	return out;
}

export function uniqSorted(a) { return [...new Set(a)].sort(cmp); }
