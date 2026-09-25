// corpus-js.mjs - extract `vscode` API usage (call-site counts) and Node built-in module usage from JS files.
//
// Parser: acorn (https://github.com/acornjs/acorn, MIT). It is NOT a dependency of tools/vsx-audit/package.json; install it
// out of tree and point NODE_PATH-style resolution at it with CORPUS_NODE_MODULES:
//   npm install --prefix "$SP/node_modules-corpus" acorn@8 acorn-walk@8
//   CORPUS_NODE_MODULES="$SP/node_modules-corpus/node_modules" node tools/vsx-audit/corpus-scan.mjs
// Without acorn the extractor degrades to the regex scan for every file (recorded as apiUsageMethod "regex").
//
// Algorithm (per file, scope-aware by function scope; block scopes are folded into their function):
//  1. declarations pass: every function/program scope records the names it declares (params, var/let/const, function
//     and class names, catch params, imports). Webpack module factories remember their module key (object key/array index).
//  2. binding pass (fixpoint, <= 4 rounds): a binding is "vscode namespace" when initialised/assigned from
//       require("vscode") | import * as / default from "vscode" | await import("vscode")
//       | wrapper(require("vscode")) e.g. __toESM, __importStar, _interopRequireWildcard, _interopNamespaceDefault
//       | webpack consumer n(ID) where module ID's factory is `module.exports = require("vscode")`
//       | another vscode binding (alias) | <ns>.default | (0, <ns>)
//     and a "member binding" (path) for `const w = vscode.window`, `const {window, Range: R} = vscode`,
//     `import {window} from "vscode"`, `const {showInformationMessage} = vscode.window`.
//  3. usage pass: each MemberExpression rooted at a binding and each non-declaration reference of a member binding
//     is one call site. The id is the first segment when it is Capitalised (class/enum: `Range`, `Uri`,
//     `StatusBarAlignment`), otherwise `namespace.member` (`window.createWebviewPanel`, `workspace.fs`); a bare
//     namespace reference records just the namespace (`window`).
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';

let acorn = null;
try {
  const base = process.env.CORPUS_NODE_MODULES;
  const req = createRequire(base ? path.join(base, 'noop.js') : import.meta.url);
  acorn = req('acorn');
} catch { acorn = null; }
export const HAVE_ACORN = !!acorn;

export const NODE_BUILTINS = ['child_process', 'worker_threads', 'net', 'http', 'https', 'http2', 'fs', 'fs/promises',
  'crypto', 'os', 'path', 'tls', 'dgram', 'dns', 'zlib', 'stream', 'vm', 'cluster', 'readline', 'util', 'events', 'url',
  'buffer', 'perf_hooks', 'async_hooks', 'inspector', 'v8', 'module', 'process', 'tty', 'string_decoder', 'assert', 'wasi'];
const BUILTIN_RE = new RegExp(
  String.raw`(?:\brequire\s*\(\s*|\bfrom\s*|\bimport\s*\(\s*|\bimport\s+)(['"])(?:node:)?(` +
  NODE_BUILTINS.map((b) => b.replace('/', '\\/')).join('|') + String.raw`)\1`, 'g');

export function nodeBuiltins(src) {
  const out = {};
  for (const m of src.matchAll(BUILTIN_RE)) out[m[2]] = (out[m[2]] || 0) + 1;
  return out;
}

export const MENTIONS_VSCODE = /["'`]vscode["'`]/;

const isFn = (n) => n.type === 'FunctionDeclaration' || n.type === 'FunctionExpression' || n.type === 'ArrowFunctionExpression';
const SKIP_KEYS = new Set(['type', 'start', 'end', 'loc', 'range', 'raw', 'regex', 'bigint', 'sourceType', '__scope', '__s', '__keys']);

function children(node, cb) {
  for (const k in node) {
    if (SKIP_KEYS.has(k)) continue;
    const v = node[k];
    if (Array.isArray(v)) { for (let i = 0; i < v.length; i++) { const c = v[i]; if (c && typeof c.type === 'string') cb(c, k, i); } }
    else if (v && typeof v.type === 'string') cb(v, k, -1);
  }
}

function patternNames(p, out) {
  if (!p) return out;
  switch (p.type) {
    case 'Identifier': out.push(p.name); break;
    case 'ObjectPattern': for (const pr of p.properties) patternNames(pr.type === 'RestElement' ? pr.argument : pr.value, out); break;
    case 'ArrayPattern': for (const e of p.elements) patternNames(e, out); break;
    case 'RestElement': patternNames(p.argument, out); break;
    case 'AssignmentPattern': patternNames(p.left, out); break;
  }
  return out;
}

const cap = (s) => /^[A-Z]/.test(s);
export function apiId(pathArr) {
  if (!pathArr.length) return null;
  if (cap(pathArr[0])) return pathArr[0];
  return pathArr.slice(0, 2).join('.');
}
const propName = (m) => (!m.computed && m.property.type === 'Identifier' ? m.property.name
  : m.computed && m.property.type === 'Literal' && typeof m.property.value === 'string' ? m.property.value : null);

// Webpack module factories whose body is `X.exports = require("vscode")`; ids are shared across split chunks
// (webpack 5 `exports.modules={1398(e){e.exports=require("vscode")}}` in the main chunk, consumers `r(1398)` in others).
const WP_VSCODE_MOD = /(?:^|[,{\[])\s*(?:["'`]([^"'`\n]{1,200})["'`]|([\w$]+))\s*(?::\s*(?:function\s*[\w$]*\s*)?\(?[\w$,\s]*\)?\s*(?:=>)?|\([\w$,\s]*\))\s*\{\s*(?:["']use strict["'];?\s*)?[\w$]+\.exports\s*=\s*require\(\s*["'`]vscode["'`]\s*\)/g;
export function webpackVscodeIds(src) {
  const ids = new Set();
  for (const m of src.matchAll(WP_VSCODE_MOD)) ids.add(m[1] ?? m[2]);
  return [...ids];
}

function analyseAst(ast, sharedModIds = [], src = null) {
  // ---- pass 1: scopes & declarations (iterative) ----
  const root = { decl: new Set(), parent: null, bind: new Map(), node: ast };
  const stack = [[ast, root, null, null, null]];
  while (stack.length) {
    const [node, scope, modKey, container, parentNode] = stack.pop();
    let s = scope;
    if (isFn(node)) {
      s = { decl: new Set(), parent: scope, bind: new Map(), node, modKey, container: modKey != null ? container : null };
      if (modKey != null && container) (container.__keys ||= new Set()).add(modKey);
      if (node.type === 'FunctionDeclaration' && node.id) scope.decl.add(node.id.name);
      if (node.type === 'FunctionExpression' && node.id) s.decl.add(node.id.name);
      for (const p of node.params) for (const n of patternNames(p, [])) s.decl.add(n);
      node.__s = s;
    } else if (node.type === 'VariableDeclaration') {
      for (const d of node.declarations) for (const n of patternNames(d.id, [])) scope.decl.add(n);
    } else if (node.type === 'ClassDeclaration' && node.id) scope.decl.add(node.id.name);
    else if (node.type === 'CatchClause' && node.param) for (const n of patternNames(node.param, [])) scope.decl.add(n);
    else if (node.type === 'ImportDeclaration') for (const sp of node.specifiers) root.decl.add(sp.local.name);
    node.__scope = s;
    children(node, (c, k, i) => {
      let mk = null;
      let cont = null;
      if (isFn(c)) {
        if (node.type === 'Property' && k === 'value') {
          mk = node.key.type === 'Identifier' ? node.key.name : String(node.key.value);
          cont = parentNode; // the ObjectExpression holding the factories
        } else if (node.type === 'ArrayExpression') { mk = String(i); cont = node; }
      }
      stack.push([c, s, mk, cont, node]);
    });
  }
  const resolve = (scope, name) => {
    for (let s = scope; s; s = s.parent) if (s.decl.has(name)) return s;
    return root;
  };
  // webpack module id -> set of module containers (the {..}/[..] holding the factories) where it means vscode.
  // Ids are scoped to their container because nested webpack runtimes (a pre-bundled dependency) reuse small ids
  // (thunder-client: id 3 is vscode in the outer runtime, `path` in an inner one). '*' = id learnt from a sibling chunk.
  const modIds = new Map(sharedModIds.map((id) => [String(id), new Set(['*'])]));
  const containerOf = (scope) => { for (let s = scope; s; s = s.parent) if (s.modKey != null) return s.container; return null; };
  // accept n(ID) when ID is the vscode factory in this container, or when this container does not define ID itself
  // (it is then resolved through the shared runtime registry); reject when this container defines ID as another module
  const isVsModCall = (id, scope) => {
    const set = modIds.get(id);
    if (!set) return false;
    const c = containerOf(scope);
    if (set.has('*') || set.has(c)) return true;
    return !(c && c.__keys && c.__keys.has(id));
  };
  const strArg = (a) => (a.type === 'Literal' ? a.value
    : a.type === 'TemplateLiteral' && a.expressions.length === 0 ? a.quasis[0].value.cooked : undefined);
  // require("vscode") / require(`vscode`) / r("vscode") where r aliases require (closure: `const r = require`)
  const isRequireVscode = (e) => e.type === 'CallExpression' && e.arguments.length >= 1 && strArg(e.arguments[0]) === 'vscode' &&
    ((e.callee.type === 'Identifier' && (e.arguments.length === 1 || /require$/i.test(e.callee.name))) ||
      (e.callee.type === 'MemberExpression' && propName(e.callee) === 'require'));
  // lazy CommonJS wrappers whose factory re-exports vscode: `const $_req = __commonJS(function(exports, module){
  // Object.assign(exports, r('vscode')) })` or esbuild `var require_vscode = __commonJS({"vscode"(exports, module){
  // module.exports = require("vscode") }})`; calling the wrapper with no args yields the namespace.
  const factories = new Set();
  const REEXPORT = /exports[\s\S]{0,80}\(\s*["'`]vscode["'`]\s*\)/;
  const isVscodeFactoryInit = (init) => {
    if (!src || init.type !== 'CallExpression' || init.arguments.length !== 1) return false;
    let f = init.arguments[0];
    if (f.type === 'ObjectExpression' && f.properties.length === 1) f = f.properties[0].value;
    if (!f || !isFn(f) || f.end - f.start > 600) return false;
    return REEXPORT.test(src.slice(f.start, f.end));
  };
  // returns path array ([] = namespace) or null
  const vsPath = (e, scope, depth = 0) => {
    if (!e || depth > 6) return null;
    switch (e.type) {
      case 'Identifier': {
        const s = resolve(scope, e.name);
        const b = s.bind.get(e.name);
        return b ? b : null;
      }
      case 'CallExpression': {
        if (isRequireVscode(e)) return [];
        if (e.arguments.length === 0 && e.callee.type === 'Identifier' && factories.has(resolve(scope, e.callee.name).node.start + ':' + e.callee.name)) return [];
        const a0 = e.arguments[0];
        if (e.arguments.length === 1 && strArg(a0) !== undefined && isVsModCall(String(strArg(a0)), scope) &&
          (e.callee.type === 'Identifier' || e.callee.type === 'MemberExpression')) return [];
        if (a0 && e.arguments.length <= 2 && e.callee.type !== 'Super') {
          if (vsPath(e.callee, scope, depth + 1)) return null;
          const p = vsPath(a0, scope, depth + 1);
          if (p && p.length === 0) return [];
        }
        return null;
      }
      case 'ImportExpression': return e.source.type === 'Literal' && e.source.value === 'vscode' ? [] : null;
      case 'AwaitExpression': return vsPath(e.argument, scope, depth + 1);
      case 'SequenceExpression': return vsPath(e.expressions[e.expressions.length - 1], scope, depth + 1);
      case 'MemberExpression': {
        const p = vsPath(e.object, scope, depth + 1);
        if (!p) return null;
        const n = propName(e);
        if (n == null) return null;
        if (p.length === 0 && n === 'default') return [];
        // only first-level aliases become bindings (`const w = vscode.window`, `const U = vscode.Uri`); anything deeper is
        // a value (window.activeTextEditor, window.tabGroups.activeTab) and must not turn later `x.foo` into API call sites
        return p.length >= 1 ? null : [n];
      }
      default: return null;
    }
  };
  const setBind = (scope, name, p) => {
    const s = resolve(scope, name);
    const old = s.bind.get(name);
    if (old && old.join('.') === p.join('.')) return false;
    if (old && old.length <= p.length) return false;
    s.bind.set(name, p);
    return true;
  };
  const destructure = (pattern, basePath, scope) => {
    let ch = false;
    for (const pr of pattern.properties) {
      if (pr.type !== 'Property' || pr.computed) continue;
      const key = pr.key.type === 'Identifier' ? pr.key.name : pr.key.value;
      let v = pr.value;
      if (v.type === 'AssignmentPattern') v = v.left;
      const p = basePath.length >= 2 ? basePath : [...basePath, key];
      if (v.type === 'Identifier') ch = setBind(scope, v.name, p) || ch;
    }
    return ch;
  };
  // ---- pass 2: bindings (fixpoint) ----
  const all = [];
  {
    const st = [ast];
    while (st.length) { const n = st.pop(); all.push(n); children(n, (c) => st.push(c)); }
  }
  for (let round = 0; round < 4; round++) {
    let changed = false;
    for (const n of all) {
      const scope = n.__scope;
      if (n.type === 'VariableDeclarator' && n.init) {
        if (n.id.type === 'Identifier' && isVscodeFactoryInit(n.init)) {
          const k = resolve(scope, n.id.name).node.start + ':' + n.id.name;
          if (!factories.has(k)) { factories.add(k); changed = true; }
          continue;
        }
        const p = vsPath(n.init, scope);
        if (p) {
          if (n.id.type === 'Identifier') changed = setBind(scope, n.id.name, p) || changed;
          else if (n.id.type === 'ObjectPattern') changed = destructure(n.id, p, scope) || changed;
        }
      } else if (n.type === 'AssignmentExpression' && n.operator === '=') {
        if (n.left.type === 'MemberExpression' && propName(n.left) === 'exports' && isRequireVscode(n.right) && scope.modKey != null) {
          if (!modIds.has(scope.modKey)) modIds.set(scope.modKey, new Set());
          const set = modIds.get(scope.modKey);
          if (!set.has(scope.container)) { set.add(scope.container); changed = true; }
          continue;
        }
        const p = vsPath(n.right, scope);
        if (p) {
          if (n.left.type === 'Identifier') changed = setBind(scope, n.left.name, p) || changed;
          else if (n.left.type === 'ObjectPattern') changed = destructure(n.left, p, scope) || changed;
        }
      } else if (n.type === 'ImportDeclaration' && n.source.value === 'vscode') {
        for (const sp of n.specifiers) {
          const p = sp.type === 'ImportSpecifier' ? [sp.imported.name ?? sp.imported.value] : [];
          changed = setBind(root, sp.local.name, p) || changed;
        }
      }
    }
    if (!changed) break;
  }
  // ---- pass 3: usage ----
  const usage = {};
  const add = (id) => { if (id) usage[id] = (usage[id] || 0) + 1; };
  const walk = [[ast, null, null]];
  while (walk.length) {
    const [n, parent, grand] = walk.pop();
    if (n.type === 'MemberExpression' && n.object.type === 'Identifier') {
      const b = resolve(n.__scope, n.object.name).bind.get(n.object.name);
      if (b) {
        let pn = propName(n);
        let host = n, hostParent = parent;
        if (pn === 'default' && b.length === 0 && parent && parent.type === 'MemberExpression' && parent.object === n) {
          // interop `x.default.window.foo` -> treat `x.default` as the namespace
          pn = propName(parent); host = parent; hostParent = grand;
        } else if (pn === 'default' && b.length === 0) pn = null;
        if (pn != null) {
          let p = [...b, pn];
          if (p.length < 2 && hostParent && hostParent.type === 'MemberExpression' && hostParent.object === host && !cap(pn)) {
            const pp = propName(hostParent);
            if (pp != null) p = [...p, pp];
          }
          add(apiId(p));
        }
      }
    } else if (n.type === 'Identifier' && parent && !(parent.type === 'MemberExpression' && parent.object === n)) {
      const b = resolve(n.__scope, n.name).bind.get(n.name);
      if (b && b.length && isReference(n, parent, grand)) add(apiId(b));
    } else if (n.type === 'VariableDeclarator' && n.id.type === 'ObjectPattern' && n.init) {
      // destructuring site: const {window, Range} = vscode -> one site per key
      const p = vsPath(n.init, n.__scope);
      if (p) for (const pr of n.id.properties) if (pr.type === 'Property' && !pr.computed) {
        add(apiId(p.length >= 2 ? p : [...p, pr.key.type === 'Identifier' ? pr.key.name : pr.key.value]));
      }
    }
    children(n, (c) => walk.push([c, n, parent]));
  }
  return { usage, modIds: [...modIds.keys()], bindingsFound: countBindings(all) };
}

function countBindings(all) {
  let n = 0;
  for (const x of all) if (x.__s) n += x.__s.bind.size;
  return n + (all[0]?.__scope?.bind.size || 0);
}

function isReference(n, p, g) {
  switch (p.type) {
    case 'MemberExpression': return p.object === n || p.computed;
    case 'Property': if (g && g.type === 'ObjectPattern') return p.computed && p.key === n;
      return p.value === n || (p.computed && p.key === n);
    case 'VariableDeclarator': return p.init === n;
    case 'FunctionDeclaration': case 'FunctionExpression': case 'ArrowFunctionExpression': return p.body === n;
    case 'ImportSpecifier': case 'ImportDefaultSpecifier': case 'ImportNamespaceSpecifier': case 'ExportSpecifier':
    case 'LabeledStatement': case 'BreakStatement': case 'ContinueStatement': case 'CatchClause': case 'RestElement':
    case 'ArrayPattern': case 'ObjectPattern': case 'ClassDeclaration': case 'ClassExpression': return false;
    case 'MethodDefinition': case 'PropertyDefinition': return p.computed && p.key === n;
    case 'AssignmentPattern': return p.right === n;
    case 'AssignmentExpression': return p.right === n;
    default: return true;
  }
}

// ---- regex fallback ---------------------------------------------------------------------------------------------
export function regexScan(src) {
  const names = new Set();
  const decl = /\b([A-Za-z_$][\w$]*)\s*=\s*(?:[A-Za-z_$][\w$.]*\()?\s*require\(\s*['"`]vscode['"`]\s*\)/g;
  for (const m of src.matchAll(decl)) names.add(m[1]);
  // heuristic for wrapped requires the regex cannot follow (closure `var module$exports$vscode = $_req_$0()`,
  // tsc `vscode_1`): an assigned identifier named vscode / vscode_N / *$vscode that is used as `name.member`
  for (const m of src.matchAll(/(?<![\w$])((?:[A-Za-z_$][\w$]*\$)?vscode(?:_\d+)?)\s*=[^=]/g)) if (src.includes(m[1] + '.')) names.add(m[1]);
  for (const m of src.matchAll(/import\s*\*\s*as\s+([A-Za-z_$][\w$]*)\s+from\s*['"]vscode['"]/g)) names.add(m[1]);
  for (const m of src.matchAll(/import\s+([A-Za-z_$][\w$]*)\s+from\s*['"]vscode['"]/g)) names.add(m[1]);
  const usage = {};
  const add = (id) => { if (id) usage[id] = (usage[id] || 0) + 1; };
  for (const nm of names) {
    const re = new RegExp(String.raw`(?<![\w$.'"\`/-])` + nm.replace(/\$/g, '\\$') + String.raw`\.([A-Za-z_$][\w$]*)(?:\.([A-Za-z_$][\w$]*))?`, 'g');
    for (const m of src.matchAll(re)) add(apiId(cap(m[1]) || !m[2] ? [m[1]] : [m[1], m[2]]));
  }
  for (const m of src.matchAll(/import\s*\{([^}]*)\}\s*from\s*['"]vscode['"]/g)) {
    for (const part of m[1].split(',')) { const k = part.trim().split(/\s+as\s+/)[0]; if (k && !k.startsWith('type ')) add(apiId([k])); }
  }
  return { usage, names: [...names] };
}

/** Analyse one JS source text. Returns {usage, method, error?}. sharedModIds: webpack vscode module ids found in sibling chunks. */
export function mayUseVscode(src, sharedModIds = []) {
  return MENTIONS_VSCODE.test(src) || sharedModIds.some((id) => src.includes(`(${/^\d+$/.test(id) ? id : JSON.stringify(id)})`));
}
export function analyseSource(src, sharedModIds = []) {
  if (!mayUseVscode(src, sharedModIds)) return { usage: {}, method: 'skip' };
  if (acorn) {
    const opts = { ecmaVersion: 'latest', allowHashBang: true, allowReturnOutsideFunction: true, allowAwaitOutsideFunction: true,
      allowImportExportEverywhere: true, allowReserved: true };
    let ast = null;
    let err = null;
    for (const sourceType of ['module', 'script']) {
      try { ast = acorn.parse(src, { ...opts, sourceType }); break; } catch (e) { err = e; }
    }
    if (ast) {
      try {
        const own = webpackVscodeIds(src).length > 0; // a file with its own vscode factory is its own runtime
        const r = analyseAst(ast, own ? [] : sharedModIds, src);
        return { usage: r.usage, method: 'acorn', webpackVscodeModules: r.modIds };
      } catch (e) { err = e; }
    }
    const r = regexScan(src);
    return { usage: r.usage, method: 'regex', error: String(err?.message || err).slice(0, 200) };
  }
  return { usage: regexScan(src).usage, method: 'regex' };
}

export function analyseFile(file) {
  const src = fs.readFileSync(file, 'utf8');
  return { ...analyseSource(src), builtins: nodeBuiltins(src) };
}
