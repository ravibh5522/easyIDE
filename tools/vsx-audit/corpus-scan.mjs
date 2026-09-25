#!/usr/bin/env node
// corpus-scan.mjs - analyse every downloaded extension (manifest + bundles + native binaries) and write
//   docs/vsx-compat/data/corpus.json        per-extension profile
//   docs/vsx-compat/data/corpus-usage.json  usage aggregates weighted by Open VSX download counts
// Input: $CACHE/fetch-state.json written by corpus-fetch.mjs (run that first). Works fully offline.
//
// JS parsing uses acorn, installed OUT OF TREE (tools/vsx-audit/package.json is owned by another script):
//   npm install --prefix "$SP/node_modules-corpus" acorn@8 acorn-walk@8
//   CORPUS_NODE_MODULES="$SP/node_modules-corpus/node_modules" node tools/vsx-audit/corpus-scan.mjs [--workers 3] [--no-cache]
// (without CORPUS_NODE_MODULES/acorn every file falls back to the regex scanner; see lib/corpus-js.mjs).
// Per-extension results are cached in $CACHE/scan/ keyed by a hash of the lib sources (use --no-cache to force).
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { Worker, isMainThread, parentPort, workerData } from 'node:worker_threads';
import { analyseManifest } from './lib/corpus-manifest.mjs';
import { analyseSource, nodeBuiltins, regexScan, HAVE_ACORN, mayUseVscode, webpackVscodeIds } from './lib/corpus-js.mjs';
import { nativeInfo } from './lib/corpus-native.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const CACHE = process.env.CORPUS_CACHE || '/root/.cache/easyide-corpus';
const OUT_DIR = path.resolve(HERE, '../../docs/vsx-compat/data');
const MAX_PARSE = 40 * 1024 * 1024;
const JS_RE = /\.(js|mjs|cjs)$/i;

function scanVersion() {
  const h = crypto.createHash('sha1');
  for (const f of ['corpus-js.mjs', 'corpus-native.mjs', 'corpus-manifest.mjs']) h.update(fs.readFileSync(path.join(HERE, 'lib', f)));
  h.update(scanExtension.toString()); // per-extension logic only; aggregation changes do not invalidate the cache
  h.update(String(HAVE_ACORN));
  return h.digest('hex').slice(0, 12);
}

function* walkFiles(dir, base = dir) {
  for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, ent.name);
    if (ent.isDirectory()) yield* walkFiles(p, base);
    else if (ent.isFile()) yield { abs: p, rel: path.relative(base, p) };
  }
}

function resolveEntry(dir, rel) {
  if (!rel) return null;
  for (const c of [rel, rel + '.js', rel + '.cjs', rel + '.mjs', path.join(rel, 'index.js')]) {
    const p = path.resolve(dir, c);
    if (fs.existsSync(p) && fs.statSync(p).isFile()) return p;
  }
  return null;
}

// ------------------------------------------------------------------------------------------------ per extension
function scanExtension(ext) {
  const root = ext.unpackedDir;
  const dir = path.join(root, 'extension');
  const pkg = JSON.parse(fs.readFileSync(path.join(dir, 'package.json'), 'utf8').replace(/^﻿/, ''));
  const m = analyseManifest(pkg);
  let unpackedBytes = 0;
  let jsFilesCount = 0;
  const nativeBinaries = [];
  const wasm = [];
  const apiUsage = {};
  const builtins = {};
  const methods = { acorn: 0, regex: 0 };
  const parseErrors = [];
  const largeFilesRegexOnly = [];
  const apiFiles = [];
  const webpackVscodeModuleFiles = [];
  // pre-pass: webpack module ids of `module.exports = require("vscode")` across all chunks of this extension
  const sharedModIds = new Set();
  const files = [...walkFiles(root)];
  for (const f of files) {
    if (!JS_RE.test(f.rel) || !f.rel.startsWith('extension' + path.sep)) continue;
    const src = fs.readFileSync(f.abs, 'utf8');
    if (/require\(\s*["'`]vscode["'`]\s*\)/.test(src)) for (const id of webpackVscodeIds(src)) sharedModIds.add(id);
  }
  const shared = [...sharedModIds];
  // a separate web-worker build (package.json `browser`) duplicates the node bundle: exclude its directory from API
  // counting when it is not an ancestor of the `main` entry (e.g. GitLens dist/browser/ vs dist/gitlens.js)
  let browserExcludeDir = null;
  if (m.main && m.browser) {
    const bdir = path.dirname(path.resolve(dir, m.browser));
    const mdir = path.dirname(path.resolve(dir, m.main));
    if (!(mdir + path.sep).startsWith(bdir + path.sep)) browserExcludeDir = bdir;
  }
  const excludedBrowserFiles = [];
  for (const f of files) {
    const st = fs.statSync(f.abs);
    unpackedBytes += st.size;
    if (!f.rel.startsWith('extension' + path.sep)) continue;
    const rel = f.rel.slice('extension/'.length);
    const ni = nativeInfo(f.abs, rel, st.size);
    if (ni) { if (ni.kind === 'wasm') wasm.push({ path: rel, bytes: st.size }); else nativeBinaries.push(ni); continue; }
    if (!JS_RE.test(rel)) continue;
    jsFilesCount++;
    const src = fs.readFileSync(f.abs, 'utf8');
    for (const [k, v] of Object.entries(nodeBuiltins(src))) builtins[k] = (builtins[k] || 0) + v;
    if (!mayUseVscode(src, shared)) continue;
    if (browserExcludeDir && f.abs.startsWith(browserExcludeDir + path.sep)) { excludedBrowserFiles.push(rel); continue; }
    let r;
    if (st.size > MAX_PARSE) {
      r = { usage: regexScan(src).usage, method: 'regex' };
      largeFilesRegexOnly.push({ path: rel, bytes: st.size });
    } else r = analyseSource(src, shared);
    if (r.method === 'skip') continue;
    methods[r.method]++;
    if (r.error) parseErrors.push({ path: rel, error: r.error });
    if (r.webpackVscodeModules?.length) webpackVscodeModuleFiles.push(rel);
    const n = Object.values(r.usage).reduce((a, b) => a + b, 0);
    if (n) apiFiles.push({ path: rel, sites: n, method: r.method });
    for (const [k, v] of Object.entries(r.usage)) apiUsage[k] = (apiUsage[k] || 0) + v;
  }
  const mainAbs = resolveEntry(dir, m.main);
  const browserAbs = resolveEntry(dir, m.browser);
  const apiUsageMethod = methods.acorn + methods.regex === 0 ? 'none'
    : methods.regex === 0 ? 'acorn' : methods.acorn === 0 ? 'regex' : 'acorn+regex';
  let license = null;
  for (const c of ['LICENSE', 'LICENSE.md', 'LICENSE.txt', 'license', 'license.md', 'LICENCE', 'LICENSE.MD']) {
    const p = path.join(dir, c);
    if (fs.existsSync(p)) { license = { file: c, head: fs.readFileSync(p, 'utf8').split('\n').slice(0, 30).join('\n') }; break; }
  }
  return {
    manifest: m,
    packageName: pkg.name, packagePublisher: pkg.publisher, packageVersion: pkg.version, packageLicense: pkg.license ?? null,
    unpackedBytes, jsFilesCount,
    mainBundleBytes: mainAbs ? fs.statSync(mainAbs).size : null,
    browserBundleBytes: browserAbs ? fs.statSync(browserAbs).size : null,
    nativeBinaries, wasmFiles: wasm, nodeBuiltins: builtins, apiUsage: sortObj(apiUsage), apiUsageMethod,
    apiUsageStats: { filesAcorn: methods.acorn, filesRegex: methods.regex, parseErrors: parseErrors.slice(0, 10),
      parseErrorCount: parseErrors.length, largeFilesRegexOnly, filesWithApiUsage: apiFiles.length,
      webpackVscodeModuleIds: shared.slice(0, 20), excludedBrowserBundleFiles: excludedBrowserFiles.slice(0, 20), topFiles: apiFiles.sort((a, b) => b.sites - a.sites).slice(0, 5), webpackVscodeModuleFiles: webpackVscodeModuleFiles.slice(0, 5) },
    vsixLicense: license,
  };
}

const sortObj = (o) => Object.fromEntries(Object.entries(o).sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0])));


// ------------------------------------------------------------------------------------------------ main thread
function blockersFor(e, s, state) {
  const b = [];
  const m = s.manifest;
  if (e.noGlibcArm64) b.push(`no glibc linux-arm64 or universal build (targets: ${e.targetsAvailable.join(', ')})`);
  const elf = s.nativeBinaries.filter((n) => n.format === 'elf');
  if (elf.length) {
    const okArm = elf.filter((n) => n.arch === 'aarch64' && n.libc !== 'musl');
    const archs = [...new Set(elf.map((n) => `${n.arch}/${n.libc}`))].join(', ');
    if (!okArm.length) b.push(`ships ELF native binaries but none for aarch64-glibc (${archs})`);
    else b.push(`ships ${okArm.length} aarch64 native binaries (${okArm.filter((n) => n.kind === 'node-addon').length} node addons) - needs native loading in proot`);
  }
  const foreign = s.nativeBinaries.filter((n) => n.format !== 'elf');
  if (!elf.length && foreign.length) b.push(`ships only non-Linux native binaries (${[...new Set(foreign.map((n) => n.format))].join(', ')})`);
  if (m.enabledApiProposals.length) b.push(`proposed API: ${m.enabledApiProposals.join(', ')}`);
  if (m.debuggers) b.push(`contributes ${m.debuggers} debuggers (debug.dap out of scope)`);
  if (m.notebooks || m.contributes.notebookRenderer) b.push('contributes notebooks/renderers (notebook.api out of scope)');
  const chat = Object.keys(s.apiUsage).filter((k) => /^(lm|chat)\b/.test(k) || /^LanguageModel|^Chat/.test(k));
  if (chat.length) b.push(`uses chat/lm API (${chat.slice(0, 5).join(', ')}${chat.length > 5 ? ', ...' : ''})`);
  if (['chatParticipants', 'languageModelTools', 'mcpServerDefinitionProviders', 'languageModelChatProviders'].some((k) => m.contributes[k])) {
    b.push('contributes chat/LM/MCP points');
  }
  if (!m.main && m.browser) b.push('web-only extension (browser entry, no main)');
  const missing = state.unresolved.filter((u) => (u.dependencyOf || []).includes(e.id) && (m.extensionDependencies || []).map((x) => x.toLowerCase()).includes(u.id.toLowerCase()));
  for (const u of missing) b.push(`extensionDependency not on Open VSX: ${u.id}`);
  if (e.deprecated) b.push('deprecated on Open VSX');
  return b;
}

function aggregate(exts, pick) {
  const total = exts.reduce((a, e) => a + (e.downloadCount || 0), 0);
  const out = {};
  for (const e of exts) {
    for (const [k, n] of Object.entries(pick(e) || {})) {
      const o = out[k] ||= { count: 0, callSites: 0, weightedDownloads: 0, extensions: [] };
      o.count++;
      o.callSites += typeof n === 'number' ? n : 0;
      o.weightedDownloads += e.downloadCount || 0;
      o.extensions.push(e.id);
    }
  }
  for (const o of Object.values(out)) o.weightShare = Number((o.weightedDownloads / total).toFixed(6));
  return Object.fromEntries(Object.entries(out).sort((a, b) => b[1].weightedDownloads - a[1].weightedDownloads || a[0].localeCompare(b[0]))
    .map(([k, o]) => [k, { count: o.count, callSites: o.callSites, weightedDownloads: o.weightedDownloads, weightShare: o.weightShare, extensions: o.extensions }]));
}

const countsOf = (arr) => arr.reduce((o, k) => ((o[k] = (o[k] || 0) + 1), o), {});
const prefix = (ev) => (ev.includes(':') ? ev.slice(0, ev.indexOf(':')) : ev);

async function main() {
  const args = process.argv.slice(2);
  const WORKERS = Number(args[args.indexOf('--workers') + 1]) || 3;
  const NO_CACHE = args.includes('--no-cache');
  const state = JSON.parse(fs.readFileSync(path.join(CACHE, 'fetch-state.json'), 'utf8'));
  const ver = scanVersion();
  const scanDir = path.join(CACHE, 'scan');
  fs.mkdirSync(scanDir, { recursive: true });
  const todo = [];
  const results = new Map();
  for (const e of state.extensions) {
    if (!e.unpackedDir) continue;
    const cp = path.join(scanDir, path.basename(e.unpackedDir) + '.json');
    if (!NO_CACHE && fs.existsSync(cp)) {
      const c = JSON.parse(fs.readFileSync(cp, 'utf8'));
      if (c.ver === ver) { results.set(e.id, c.res); continue; }
    }
    todo.push(e);
  }
  // biggest first for better packing
  todo.sort((a, b) => (b.vsixBytes || 0) - (a.vsixBytes || 0));
  console.log(`scan version ${ver}; acorn=${HAVE_ACORN}; cached=${results.size}; to scan=${todo.length}`);
  await new Promise((resolve) => {
    if (!todo.length) return resolve();
    let active = 0;
    const queue = [...todo];
    const startWorker = () => {
      const w = new Worker(fileURLToPath(import.meta.url), { resourceLimits: { maxOldGenerationSizeMb: 4096 } });
      const feed = () => {
        const e = queue.shift();
        if (!e) { w.terminate(); if (--active === 0) resolve(); return; }
        w.postMessage(e);
      };
      w.on('message', (msg) => {
        const e = todo.find((x) => x.id === msg.id);
        if (msg.ok) {
          results.set(msg.id, msg.res);
          fs.writeFileSync(path.join(scanDir, path.basename(e.unpackedDir) + '.json'), JSON.stringify({ ver, res: msg.res }));
        } else console.error('FAILED', msg.id, msg.error);
        console.log(`scanned ${results.size}/${state.extensions.length} ${msg.id}`);
        feed();
      });
      w.on('error', (err) => { console.error('worker error', err); active--; if (queue.length) { active++; startWorker(); } else if (active === 0) resolve(); });
      active++;
      feed();
    };
    for (let i = 0; i < Math.min(WORKERS, todo.length); i++) startWorker();
  });

  const exts = [];
  for (const e of state.extensions) {
    const s = results.get(e.id);
    if (!s) { console.warn('no scan result for', e.id); continue; }
    const m = s.manifest;
    const declarativeOnly = !m.main && !m.browser;
    const spawns = (s.nodeBuiltins.child_process || 0) > 0;
    exts.push({
      id: e.id, displayName: e.displayName, version: e.version, preRelease: e.preRelease, publishedAt: e.publishedAt,
      downloadCount: e.downloadCount, averageRating: e.averageRating, rank: e.rank, inTop150: e.inTop, mustWork: e.mustWork,
      ...(e.dependencyOf ? { dependencyOf: e.dependencyOf } : {}),
      declarativeOnly, license: e.license, vsixLicenseFile: s.vsixLicense?.file ?? null,
      verifiedPublisher: e.verified, namespaceAccess: e.namespaceAccess, publishedBy: e.publishedBy, deprecated: e.deprecated,
      targetsAvailable: e.targetsAvailable, defaultTarget: e.defaultTarget, targetChosen: e.targetChosen, targetReason: e.targetReason,
      noGlibcArm64: e.noGlibcArm64, sha256Verified: e.sha256Verified, signatureAvailable: e.signatureAvailable,
      files: e.files, vsixBytes: e.vsixBytes, unpackedBytes: s.unpackedBytes,
      ...m,
      mainBundleBytes: s.mainBundleBytes, browserBundleBytes: s.browserBundleBytes, jsFilesCount: s.jsFilesCount,
      nativeBinaries: s.nativeBinaries, wasmFiles: s.wasmFiles,
      nodeBuiltins: s.nodeBuiltins, spawnsProcesses: spawns,
      apiUsageMethod: s.apiUsageMethod, apiUsageStats: s.apiUsageStats,
      apiUsage: s.apiUsage,
      blockers: blockersFor(e, s, state),
    });
  }
  exts.sort((a, b) => (a.rank ?? 1e9) - (b.rank ?? 1e9) || b.downloadCount - a.downloadCount);
  const totalDownloads = exts.reduce((a, e) => a + (e.downloadCount || 0), 0);
  const meta = {
    snapshotAt: state.meta.snapshotAt,
    fetchedAt: state.meta.generatedAt,
    scannedAt: new Date().toISOString(),
    scanVersion: ver,
    sources: {
      search: state.meta.searchPages.map((p) => p.url),
      searchTotalSize: state.meta.searchPages[0]?.totalSize,
      perExtension: 'https://open-vsx.org/api/{namespace}/{name} and https://open-vsx.org/api/{namespace}/{name}/{target}/{version}',
    },
    method: [
      'Top code extensions (package.json has main or browser) by Open VSX downloadCount, plus the must-work list and their transitive extensionDependencies/extensionPack members.',
      'Target: linux-arm64 > universal > fallback (noGlibcArm64=true, analysis only). SHA-256 verified against the registry .sha256 file.',
      'Manifest analysis: tools/vsx-audit/lib/corpus-manifest.mjs (implicit activation events mirror VS Code activationEventsGenerator registrations).',
      'API usage: tools/vsx-audit/lib/corpus-js.mjs - acorn AST, scope-aware binding tracking of the vscode module (require/import/esbuild __toESM/tslib __importStar/webpack module ids), call-site counts; regex fallback for unparsable or >40MB files.',
      'Native binaries: ELF header e_machine + PT_INTERP / GLIBC_ symbol versions (tools/vsx-audit/lib/corpus-native.mjs).',
      'Weights: downloadCount at snapshot time; weightShare = weightedDownloads / totalDownloads over all extensions in this file.',
    ],
    counts: {
      extensions: exts.length,
      top150Code: exts.filter((e) => e.inTop150).length,
      mustWork: exts.filter((e) => e.mustWork).length,
      dependencyOnly: exts.filter((e) => !e.inTop150 && !e.mustWork).length,
      declarativeInTopRange: state.declarative.length,
      totalDownloads,
      totalVsixBytes: exts.reduce((a, e) => a + (e.vsixBytes || 0), 0),
      totalUnpackedBytes: exts.reduce((a, e) => a + (e.unpackedBytes || 0), 0),
    },
    mustWorkList: state.meta.mustWork,
    unresolved: state.unresolved,
  };
  fs.mkdirSync(OUT_DIR, { recursive: true });
  const corpus = { meta, declarative: state.declarative, extensions: exts };
  writeJson(path.join(OUT_DIR, 'corpus.json'), corpus);

  const code = exts;
  const usage = {
    meta: { snapshotAt: meta.snapshotAt, scanVersion: ver, totalDownloads, extensions: code.length,
      note: 'count = #extensions; callSites = summed occurrences (api: member-expression call sites; menus: items; nodeBuiltins: import sites); weightShare = weightedDownloads/totalDownloads.' },
    api: aggregate(code, (e) => e.apiUsage),
    contributes: aggregate(code, (e) => e.contributes),
    activationEvents: aggregate(code, (e) => countsOf(e.activationEvents.map(prefix))),
    implicitActivationEvents: aggregate(code, (e) => countsOf(e.implicitActivationFromContributes.map(prefix))),
    menus: aggregate(code, (e) => e.menus),
    whenKeys: aggregate(code, (e) => Object.fromEntries(e.whenKeys.map((k) => [k, 1]))),
    nodeBuiltins: aggregate(code, (e) => e.nodeBuiltins),
    proposals: aggregate(code, (e) => Object.fromEntries(e.enabledApiProposals.map((k) => [k, 1]))),
    engines: aggregate(code, (e) => (e.engines?.vscode ? { [e.engines.vscode]: 1 } : {})),
  };
  writeJson(path.join(OUT_DIR, 'corpus-usage.json'), usage);
  console.log(`wrote ${exts.length} extensions; totalDownloads=${totalDownloads}`);
}

/** Pretty-print with 1-space indent, but keep leaf arrays/objects of scalars on one line to stay under 3 MB. */
function writeJson(file, obj) {
  const s = JSON.stringify(obj, null, 1).replace(/\[\n(\s+("[^"\n]*"|-?[\d.e+]+|true|false|null),?\n)+\s*\]/g,
    (blk) => '[' + blk.slice(1, -1).split('\n').map((x) => x.trim()).filter(Boolean).join(' ') + ']');
  fs.writeFileSync(file, s + '\n');
  console.log(`${file}: ${(s.length / 1e6).toFixed(2)} MB`);
}

if (!isMainThread) {
  parentPort.on('message', (ext) => {
    try { parentPort.postMessage({ id: ext.id, ok: true, res: scanExtension(ext) }); }
    catch (e) { parentPort.postMessage({ id: ext.id, ok: false, error: String(e.stack || e) }); }
  });
} else {
  await main();
}
