// contributes.* extension points, activation events and contributes.menus locations.
import fs from 'node:fs';
import path from 'node:path';
import { ts, parse, lineOf, strValue, descValue, prop, propName, walk, isTestPath, cmp, sortBy } from './vscode-util.mjs';
import { parseContributionPoints, parseActivationEvents, parseMenuSection } from './vscode-docs.mjs';

/** Plain text search in built-in extensions (extensions/<name>/src|package.json) for a literal. */
function searchBuiltinExtensions(vs, needle, max = 3) {
	if (!searchBuiltinExtensions.files) {
		searchBuiltinExtensions.files = walk(vs, 'extensions', rel => /\/(src|client\/src|server\/src)\//.test(rel) && /\.ts$/.test(rel) && !isTestPath(rel));
	}
	const out = [];
	for (const rel of searchBuiltinExtensions.files) {
		const lines = fs.readFileSync(path.join(vs, rel), 'utf8').split('\n');
		for (let i = 0; i < lines.length && out.length < max; i++) {
			if (lines[i].includes(needle)) { out.push(`${rel}:${i + 1}`); }
		}
		if (out.length >= max) { break; }
	}
	return out;
}

export function buildContributes(vs, facts, docCP, stableProduct) {
	const docPoints = new Map(parseContributionPoints(docCP).map(p => [p.point, p]));
	const epKind = stableProduct && stableProduct.extensionPointExtensionKind || {};
	const items = [];
	const seen = new Set();
	for (const ep of facts.extensionPoints) {
		const names = ep.names.length ? ep.names : [`<unresolved:${ep.pointExpr}>`];
		const registeredAt = ep.registeredDirectly ? [`${ep.file}:${ep.line}`] : facts.registerExtPointCalls.filter(c => c.arg === ep.declName).map(c => c.ref);
		for (const point of names) {
			seen.add(point);
			const doc = docPoints.get(point);
			const o = {
				point,
				registered: true,
				file: ep.file,
				line: ep.line,
				ref: `${ep.file}:${ep.line}`,
				registeredAt: registeredAt.sort(cmp),
				documented: !!doc,
			};
			if (doc) { o.docsMdLine = doc.mdLine; }
			o.description = ep.description ?? null;
			if (ep.schemaRef) { o.schemaRef = ep.schemaRef; }
			o.activationEventsGenerator = ep.activationEventsGenerator;
			if (ep.implicitEventPrefixes.length) { o.implicitEventPrefixes = ep.implicitEventPrefixes; }
			const checks = facts.proposalChecks.get(ep.file);
			o.proposalChecksInFile = checks ? [...checks].sort(cmp) : [];
			if (ep.defaultExtensionKind) { o.defaultExtensionKind = ep.defaultExtensionKind; }
			if (ep.canHandleResolver !== undefined) { o.canHandleResolver = ep.canHandleResolver; }
			if (ep.deps) { o.deps = ep.deps; }
			if (epKind[point]) { o.productExtensionPointExtensionKind = epKind[point]; }
			items.push(o);
		}
	}
	for (const [point, doc] of docPoints) {
		if (seen.has(point)) { continue; }
		items.push({ point, registered: false, documented: true, docsMdLine: doc.mdLine, handledByBuiltinExtensionRefs: searchBuiltinExtensions(vs, `'${point}'`).concat(searchBuiltinExtensions(vs, `.${point}`)).slice(0, 3) });
	}
	const sorted = sortBy(items, i => i.point);
	return {
		items: sorted,
		summary: {
			registered: sorted.filter(i => i.registered).length,
			documented: sorted.filter(i => i.documented).length,
			registeredNotDocumented: sorted.filter(i => i.registered && !i.documented).map(i => i.point),
			documentedNotRegistered: sorted.filter(i => !i.registered).map(i => i.point),
			withActivationEventsGenerator: sorted.filter(i => i.activationEventsGenerator).map(i => i.point),
		},
	};
}

function schemaActivationEvents(vs) {
	const rel = 'src/vs/workbench/services/extensions/common/extensionsRegistry.ts';
	const sf = parse(vs, rel);
	const out = [];
	const visit = (n, inAE) => {
		if (ts.isPropertyAssignment(n) && propName(n.name) === 'activationEvents') { inAE = true; }
		if (inAE && ts.isObjectLiteralExpression(n)) {
			const label = strValue(prop(n, 'label'));
			const body = strValue(prop(n, 'body'));
			if (label !== undefined && body !== undefined) {
				out.push({ label, body, description: descValue(prop(n, 'description')), ref: `${rel}:${lineOf(sf, n)}` });
			}
		}
		ts.forEachChild(n, c => visit(c, inAE));
	};
	visit(sf, false);
	return out;
}

const EVENT_NAME_RE = /^(on[A-Z]\w*|workspaceContains|\*)$/;

export function buildActivationEvents(vs, facts, docAE) {
	const map = new Map();
	const get = (name) => {
		let e = map.get(name);
		if (!e) {
			e = { name, documented: false, inManifestSchema: false, implicit: false, implicitFrom: [], takesArgument: false, activateCallSites: 0, _refs: [], _prio: [], _calls: [] };
			map.set(name, e);
		}
		return e;
	};
	for (const d of parseActivationEvents(docAE)) {
		const e = get(d.name);
		e.documented = true;
		e.docsMdLine = d.mdLine;
		if (d.docTakesArgument) { e.takesArgument = true; }
	}
	for (const s of schemaActivationEvents(vs)) {
		const name = s.body.split(':')[0];
		const e = get(name);
		e.inManifestSchema = true;
		e.schemaRef = s.ref;
		if (s.description) { e.schemaDescription = s.description; }
		if (s.body.includes(':')) { e.takesArgument = true; }
		e._prio.push(s.ref);
	}
	for (const ep of facts.extensionPoints) {
		for (const pfx of ep.implicitEventPrefixes) {
			const name = pfx.split(':')[0];
			if (!EVENT_NAME_RE.test(name)) { continue; }
			const e = get(name);
			e.implicit = true;
			for (const p of ep.names) { if (!e.implicitFrom.includes(p)) { e.implicitFrom.push(p); } }
			if (pfx.includes(':')) { e.takesArgument = true; }
			e._prio.push(`${ep.file}:${ep.line}`);
		}
	}
	for (const c of facts.activateCalls) {
		if (!c.prefix) { continue; }
		const name = c.prefix.split(':')[0];
		if (!EVENT_NAME_RE.test(name)) { continue; }
		const e = get(name);
		e.activateCallSites++;
		if (c.prefix.includes(':')) { e.takesArgument = true; }
		e._calls.push(`${c.file}:${c.line}`);
	}
	// string literal / template head references (e.g. ext host side checks, workspaceContains scanner)
	for (const e of map.values()) {
		const refs = [];
		if (e.name !== '*') {
			for (const [t, rs] of facts.strings) {
				if (t === e.name || t.startsWith(e.name + ':')) { refs.push(...rs); }
			}
			for (const [t, rs] of facts.templateHeads) {
				if (t.startsWith(e.name + ':')) { refs.push(...rs); }
			}
		}
		e._refs = refs.sort(cmp);
	}
	const items = sortBy([...map.values()], e => e.name).map(e => {
		const all = [...new Set([...e._calls.sort(cmp), ...e._prio.sort(cmp), ...e._refs])];
		const o = { event: e.takesArgument ? `${e.name}:` : e.name, name: e.name, documented: e.documented };
		if (e.docsMdLine) { o.docsMdLine = e.docsMdLine; }
		o.inManifestSchema = e.inManifestSchema;
		if (e.schemaDescription) { o.schemaDescription = e.schemaDescription; }
		o.implicit = e.implicit;
		if (e.implicitFrom.length) { o.implicitFrom = e.implicitFrom.sort(cmp); }
		o.takesArgument = e.takesArgument;
		o.activateCallSites = e.activateCallSites;
		o.sourceRefCount = all.length;
		o.sourceRefs = all.slice(0, 5);
		return o;
	});
	return {
		items,
		summary: {
			total: items.length,
			documented: items.filter(i => i.documented).length,
			inManifestSchema: items.filter(i => i.inManifestSchema).length,
			implicit: items.filter(i => i.implicit).map(i => i.event),
			undocumented: items.filter(i => !i.documented).map(i => i.event),
			documentedButNoSourceRef: items.filter(i => i.documented && i.sourceRefCount === 0).map(i => i.event),
			activateByEventCallSites: facts.activateCalls.length,
			dynamicCallSites: facts.activateCalls.filter(c => !c.prefix || !EVENT_NAME_RE.test(c.prefix.split(':')[0])).map(c => `${c.file}:${c.line} ${c.raw}`).sort(cmp),
		},
	};
}

export function buildMenus(vs, docCP) {
	const rel = 'src/vs/workbench/services/actions/common/menusExtensionPoint.ts';
	const actionsRel = 'src/vs/platform/actions/common/actions.ts';
	const sf = parse(vs, rel);
	const init = (() => {
		let f;
		const visit = (n) => { if (!f && ts.isVariableDeclaration(n) && n.name.getText() === 'apiMenus') { f = n.initializer; } ts.forEachChild(n, visit); };
		visit(sf);
		return f;
	})();
	if (!init || !ts.isArrayLiteralExpression(init)) { throw new Error('apiMenus not found'); }
	// MenuId statics
	const asf = parse(vs, actionsRel);
	const menuIds = new Map();
	const visit = (n) => {
		if (ts.isClassDeclaration(n) && n.name && n.name.text === 'MenuId') {
			for (const m of n.members) {
				if (ts.isPropertyDeclaration(m) && m.modifiers && m.modifiers.some(x => x.kind === ts.SyntaxKind.StaticKeyword) && m.initializer && ts.isNewExpression(m.initializer) && m.initializer.expression.getText() === 'MenuId') {
					menuIds.set(propName(m.name), { line: lineOf(asf, m), debugName: strValue(m.initializer.arguments && m.initializer.arguments[0]) });
				}
			}
		}
		ts.forEachChild(n, visit);
	};
	visit(asf);
	const docKeys = parseMenuSection(docCP);
	const items = init.elements.filter(ts.isObjectLiteralExpression).map(o => {
		const key = strValue(prop(o, 'key'));
		const idExpr = prop(o, 'id');
		const menuId = idExpr ? idExpr.getText().replace(/^MenuId\./, '') : undefined;
		const proposed = strValue(prop(o, 'proposed'));
		const ss = prop(o, 'supportsSubmenus');
		const mi = menuIds.get(menuId);
		const it = {
			key,
			menuId,
			proposed: proposed ?? null,
			supportsSubmenus: ss ? ss.kind === ts.SyntaxKind.TrueKeyword : true,
			description: descValue(prop(o, 'description')) ?? null,
			ref: `${rel}:${lineOf(sf, o)}`,
			menuIdRef: mi ? `${actionsRel}:${mi.line}` : null,
			documented: docKeys.has(key),
		};
		return it;
	});
	const sorted = sortBy(items, i => i.key);
	return {
		items: sorted,
		summary: {
			apiMenus: sorted.length,
			proposedGated: sorted.filter(i => i.proposed).length,
			stable: sorted.filter(i => !i.proposed).length,
			noSubmenus: sorted.filter(i => !i.supportsSubmenus).map(i => i.key),
			documented: sorted.filter(i => i.documented).length,
			stableUndocumented: sorted.filter(i => !i.proposed && !i.documented).map(i => i.key),
			menuIdStatics: menuIds.size,
			menuIdStaticsRef: actionsRel,
		},
	};
}
