// Shared helpers for extract-vscode.mjs (VS Code source surface extraction).
// Pure functions + a cached TypeScript parser. No network access here.
import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import ts from 'typescript';

export { ts };

/** Recursively list files under `dir` (absolute) matching `filter(relPath)`, sorted, relative to `root`. */
export function walk(root, dir, filter) {
	const out = [];
	const rec = (abs) => {
		const entries = fs.readdirSync(abs, { withFileTypes: true }).sort((a, b) => cmp(a.name, b.name));
		for (const e of entries) {
			const p = path.join(abs, e.name);
			if (e.isDirectory()) {
				if (e.name === 'node_modules' || e.name === '.git') { continue; }
				rec(p);
			} else if (e.isFile()) {
				const rel = toPosix(path.relative(root, p));
				if (filter(rel)) { out.push(rel); }
			}
		}
	};
	rec(path.join(root, dir));
	return out;
}

export function toPosix(p) { return p.split(path.sep).join('/'); }

/** Deterministic string compare (code unit order, locale independent). */
export function cmp(a, b) { return a < b ? -1 : a > b ? 1 : 0; }

export function isTestPath(rel) {
	return /(^|\/)test(\/|$)/.test(rel) || /\.test\.ts$/.test(rel) || /(^|\/)fixtures?\//.test(rel);
}

const sfCache = new Map();
/** Parse a file (relative to root) once, returning a ts.SourceFile (setParentNodes = true). */
export function parse(root, rel) {
	let sf = sfCache.get(rel);
	if (!sf) {
		const text = fs.readFileSync(path.join(root, rel), 'utf8');
		sf = ts.createSourceFile(rel, text, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS);
		sfCache.set(rel, sf);
	}
	return sf;
}

export function lineOf(sf, node) {
	return sf.getLineAndCharacterOfPosition(node.getStart(sf)).line + 1;
}

export function ref(sf, node) { return `${sf.fileName}:${lineOf(sf, node)}`; }

export function fileStats(root, rel) {
	const buf = fs.readFileSync(path.join(root, rel));
	let lines = 0;
	for (const b of buf) { if (b === 10) { lines++; } }
	if (buf.length && buf[buf.length - 1] !== 10) { lines++; }
	return { path: rel, lines, bytes: buf.length };
}

export function sha256(text) { return createHash('sha256').update(text).digest('hex'); }

/** Plain string value of a string-ish literal node, else undefined. */
export function strValue(node) {
	if (!node) { return undefined; }
	if (ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node)) { return node.text; }
	return undefined;
}

/** Static prefix of a string/template literal: 'a' -> 'a', `a:${x}` -> 'a:' (dynamic: true). */
export function strPrefix(node) {
	const v = strValue(node);
	if (v !== undefined) { return { text: v, dynamic: false }; }
	if (node && ts.isTemplateExpression(node)) { return { text: node.head.text, dynamic: true }; }
	return undefined;
}

/** Resolve a description-ish expression: 'x', localize('k','x'), nls.localize2(...), {value:'x'}. */
export function descValue(node) {
	if (!node) { return undefined; }
	const s = strValue(node);
	if (s !== undefined) { return s; }
	if (ts.isCallExpression(node)) {
		const callee = node.expression.getText();
		if (/(^|\.)localize2?$/.test(callee) && node.arguments.length >= 2) {
			return strValue(node.arguments[1]) ?? (ts.isTemplateExpression(node.arguments[1]) ? node.arguments[1].getText() : undefined);
		}
	}
	if (ts.isParenthesizedExpression(node) || ts.isAsExpression(node)) { return descValue(node.expression); }
	return undefined;
}

/** Property initializer by name in an object literal. */
export function prop(obj, name) {
	if (!obj || !ts.isObjectLiteralExpression(obj)) { return undefined; }
	for (const p of obj.properties) {
		if ((ts.isPropertyAssignment(p) || ts.isShorthandPropertyAssignment(p) || ts.isMethodDeclaration(p)) && p.name && propName(p.name) === name) {
			return ts.isPropertyAssignment(p) ? p.initializer : p;
		}
	}
	return undefined;
}

export function propName(n) {
	if (ts.isIdentifier(n) || ts.isStringLiteral(n) || ts.isNumericLiteral(n) || ts.isPrivateIdentifier(n)) { return n.text; }
	if (ts.isComputedPropertyName(n)) { return `[${n.expression.getText()}]`; }
	return n.getText();
}

/** Find top-level/any variable declaration named `name` in sf; return its initializer. */
export function findVarInit(sf, name) {
	let found;
	const visit = (n) => {
		if (found) { return; }
		if (ts.isVariableDeclaration(n) && ts.isIdentifier(n.name) && n.name.text === name && n.initializer) { found = n.initializer; return; }
		ts.forEachChild(n, visit);
	};
	visit(sf);
	return found;
}

/** Collapse whitespace; first sentence of a doc comment, max `max` chars. */
export function firstSentence(text, max = 160) {
	if (!text) { return undefined; }
	const flat = text.replace(/\{@link(?:code|plain)?\s+([^}|\s]+)(?:[|\s]([^}]*))?\}/g, (_m, a, b) => (b && b.trim()) || a)
		.replace(/\s+/g, ' ').trim();
	const m = /^(.*?[.!?])(\s|$)/.exec(flat);
	let s = m ? m[1] : flat;
	if (s.length > max) { s = s.slice(0, max - 1).trimEnd() + '…'; }
	return s;
}

export function jsDocText(node) {
	const docs = node.jsDoc;
	if (!docs || !docs.length) { return undefined; }
	const c = docs[docs.length - 1].comment;
	if (!c) { return undefined; }
	return typeof c === 'string' ? c : c.map(p => p.text ?? p.getText()).join('');
}

export function hasDeprecatedTag(node) {
	return ts.getJSDocTags(node).some(t => t.tagName.text === 'deprecated');
}

export function gitInfo(repo) {
	const out = execFileSync('git', ['-C', repo, 'log', '-1', '--format=%H%n%cI'], { encoding: 'utf8' }).trim().split('\n');
	return { sha: out[0], date: out[1] };
}

/** Sort array of objects by a key function (string), stable & deterministic. */
export function sortBy(arr, keyFn) {
	return arr.map((v, i) => [keyFn(v), i, v]).sort((a, b) => cmp(a[0], b[0]) || a[1] - b[1]).map(x => x[2]);
}

export function writeJson(file, obj) {
	fs.mkdirSync(path.dirname(file), { recursive: true });
	fs.writeFileSync(file, JSON.stringify(obj, null, '\t') + '\n');
}

/** Case-insensitive lookup of an object key. */
export function getCI(obj, key) {
	const k = Object.keys(obj).find(x => x.toLowerCase() === key.toLowerCase());
	return k === undefined ? undefined : { key: k, value: obj[k] };
}
