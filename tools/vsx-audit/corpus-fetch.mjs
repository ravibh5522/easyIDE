#!/usr/bin/env node
// corpus-fetch.mjs - build and download the Open VSX extension corpus for the easyIDE VS Code compatibility audit.
//
// Usage (Node >= 22.21; no npm deps):
//   node tools/vsx-audit/corpus-fetch.mjs [--no-download] [--size 150]
//   CORPUS_OFFLINE=1 node tools/vsx-audit/corpus-fetch.mjs      # re-run purely from the cache
// Env: CORPUS_CACHE (default /root/.cache/easyide-corpus), CORPUS_API_CONCURRENCY (default 4, keep <= 4),
//      CORPUS_DL_CONCURRENCY (default 3, keep <= 3). Behind a proxy the script re-execs itself with NODE_USE_ENV_PROXY=1.
//
// What it does:
//  1. Pages https://open-vsx.org/api/-/search?sortBy=downloadCount&sortOrder=desc&size=100&offset=N until it has
//     >= --size "code" extensions (package.json has `main` or `browser`). Extensions without main/browser (themes, icon
//     themes, language packs, grammar-only, extension packs) are recorded as `declarative` and not counted.
//  2. Adds the must-work list (regardless of rank) and, transitively, their extensionDependencies / extensionPack members
//     (flagged `dependencyOf`).
//  3. Chooses a target per extension: linux-arm64 > universal > otherwise noGlibcArm64=true and a fallback target is
//     downloaded for ANALYSIS ONLY (linux-x64 > linux-armhf > alpine-arm64 > alpine-x64 > darwin-arm64 > ... > web).
//  4. Downloads each .vsix to $CACHE/vsix/, verifies SHA-256 against the registry's `.sha256` file, unzips into
//     $CACHE/x/<id>-<version>-<target>/ and writes $CACHE/fetch-state.json (input for corpus-scan.mjs).
// All API JSON is cached under $CACHE/api/ (see lib/corpus-http.mjs); the snapshot time is pinned in $CACHE/api/snapshot.json.
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { ensureProxyEnv, getJson, getCached, download, limiter, CACHE, API_DIR, VSIX_DIR, X_DIR } from './lib/corpus-http.mjs';

ensureProxyEnv();

const args = process.argv.slice(2);
const NO_DOWNLOAD = args.includes('--no-download');
const SIZE = Number(args[args.indexOf('--size') + 1]) || 150;
const API = 'https://open-vsx.org/api';
const SEARCH = (off) => `${API}/-/search?sortBy=downloadCount&sortOrder=desc&size=100&offset=${off}`;

export const MUST_WORK = [
  'eamodio.gitlens', 'Anthropic.claude-code', 'esbenp.prettier-vscode', 'dbaeumer.vscode-eslint', 'redhat.vscode-yaml',
  'redhat.java', 'golang.Go', 'rust-lang.rust-analyzer', 'ms-python.python', 'charliermarsh.ruff', 'meta.pyrefly',
  'llvm-vs-code-extensions.vscode-clangd', 'Vue.volar', 'bradlc.vscode-tailwindcss', 'GitHub.vscode-pull-request-github',
  'ms-azuretools.vscode-docker', 'Shopify.ruby-lsp', 'Dart-Code.dart-code', 'PKief.material-icon-theme',
  'mhutchie.git-graph', 'usernamehw.errorlens', 'streetsidesoftware.code-spell-checker',
];

const FALLBACK_ORDER = ['linux-x64', 'linux-armhf', 'alpine-arm64', 'alpine-x64', 'darwin-arm64', 'darwin-x64',
  'win32-arm64', 'win32-x64', 'web'];

function chooseTarget(targets) {
  if (targets.includes('linux-arm64')) return { target: 'linux-arm64', reason: 'linux-arm64 build published', noGlibcArm64: false };
  if (targets.includes('universal')) return { target: 'universal', reason: 'universal (platform-neutral) build', noGlibcArm64: false };
  const t = FALLBACK_ORDER.find((x) => targets.includes(x)) || targets[0];
  return {
    target: t,
    reason: `no linux-arm64 or universal build (available: ${targets.join(', ')}); ${t} downloaded for analysis only` +
      (targets.some((x) => x.startsWith('alpine-arm64')) ? ' (alpine-arm64 is musl, does not run in the glibc guest)' : ''),
    noGlibcArm64: true,
  };
}

const lc = (s) => s.toLowerCase();

async function loadExt(id) {
  const dot = id.indexOf('.');
  const meta = await getJson(`${API}/${id.slice(0, dot)}/${id.slice(dot + 1)}`);
  if (!meta || meta.error) return null;
  const targets = Object.keys(meta.downloads || {}).sort();
  const choice = chooseTarget(targets.length ? targets : [meta.targetPlatform || 'universal']);
  let tmeta = meta;
  if ((meta.targetPlatform || 'universal') !== choice.target) {
    tmeta = await getJson(`${API}/${meta.namespace}/${meta.name}/${choice.target}/${meta.version}`) || meta;
  }
  const manifest = tmeta.files?.manifest ? await getJson(tmeta.files.manifest) : null;
  return { meta, tmeta, targets, choice, manifest };
}

function snapshotInfo() {
  const p = path.join(API_DIR, 'snapshot.json');
  if (fs.existsSync(p)) return JSON.parse(fs.readFileSync(p, 'utf8'));
  const s = { snapshotAt: new Date().toISOString() };
  fs.mkdirSync(API_DIR, { recursive: true });
  fs.writeFileSync(p, JSON.stringify(s, null, 1));
  return s;
}

function declarativeKind(m) {
  const c = m?.contributes || {};
  if (m?.extensionPack?.length) return 'extensionPack';
  if (c.localizations) return 'languagePack';
  if (c.iconThemes) return 'iconTheme';
  if (c.productIconThemes) return 'productIconTheme';
  if (c.themes) return 'colorTheme';
  if (c.grammars || c.languages) return 'language/grammar';
  if (c.snippets) return 'snippets';
  if (c.keybindings) return 'keymap';
  return m ? 'other' : 'manifest-unavailable';
}

async function main() {
  const snap = snapshotInfo();
  const byId = new Map();
  const declarative = [];
  const searchPages = [];
  let codeCount = 0;
  let rank = 0;
  for (let off = 0; codeCount < SIZE; off += 100) {
    const page = await getJson(SEARCH(off));
    if (!page) throw new Error('search page failed at offset ' + off);
    searchPages.push({ url: SEARCH(off), count: page.extensions.length, totalSize: page.totalSize });
    const loaded = await Promise.all(page.extensions.map((e) => loadExt(`${e.namespace}.${e.name}`)));
    for (let i = 0; i < page.extensions.length && codeCount < SIZE; i++) {
      rank++;
      const e = page.extensions[i];
      const L = loaded[i];
      const id = `${e.namespace}.${e.name}`;
      if (byId.has(lc(id))) continue;
      const code = !!(L?.manifest && (L.manifest.main || L.manifest.browser));
      if (!code) {
        declarative.push({ id, rank, downloadCount: e.downloadCount, kind: declarativeKind(L?.manifest), version: e.version });
        continue;
      }
      codeCount++;
      byId.set(lc(id), { id, rank, searchDownloadCount: e.downloadCount, inTop: true, mustWork: false, L });
    }
    if (!page.extensions.length) break;
    console.log(`search offset ${off}: code=${codeCount} declarative=${declarative.length}`);
  }
  // must-work + transitive deps/packs
  const queue = MUST_WORK.map((id) => ({ id, mustWork: true, parent: null }));
  while (queue.length) {
    const { id, mustWork, parent } = queue.shift();
    let rec = byId.get(lc(id));
    if (!rec) {
      const L = await loadExt(id);
      if (!L) { console.warn('UNRESOLVED', id); byId.set(lc(id), { id, unresolved: true, mustWork, dependencyOf: parent ? [parent] : undefined }); continue; }
      const decl = declarative.find((x) => lc(x.id) === lc(id));
      rec = { id: `${L.meta.namespace}.${L.meta.name}`, rank: decl ? decl.rank : null, inTop: false, mustWork: false, L };
      byId.set(lc(id), rec);
    }
    if (rec.unresolved) continue;
    if (mustWork) rec.mustWork = true;
    if (parent) {
      rec.dependencyOf = [...new Set([...(rec.dependencyOf || []), parent])];
    }
    const m = rec.L.manifest || {};
    const root = mustWork ? rec.id : parent;
    for (const d of [...(m.extensionDependencies || []), ...(m.extensionPack || [])]) {
      const ex = byId.get(lc(d));
      if (ex && (ex.dependencyOf || []).includes(root)) continue;
      if (lc(d) === lc(rec.id) || lc(d) === lc(root)) continue;
      queue.push({ id: d, mustWork: false, parent: root });
    }
  }

  const list = [...byId.values()].filter((r) => !r.unresolved);
  const dlLimit = limiter(Number(process.env.CORPUS_DL_CONCURRENCY || 3));
  let done = 0;
  const out = await Promise.all(list.map((r) => dlLimit(async () => {
    const { meta, tmeta, targets, choice } = r.L;
    const id = `${meta.namespace}.${meta.name}`;
    const files = tmeta.files || {};
    const rec = {
      id, displayName: meta.displayName, version: meta.version, preRelease: !!meta.preRelease, publishedAt: tmeta.timestamp || meta.timestamp,
      downloadCount: meta.downloadCount, averageRating: meta.averageRating ?? null, reviewCount: meta.reviewCount ?? null,
      rank: r.rank, inTop: r.inTop, mustWork: r.mustWork, dependencyOf: r.dependencyOf,
      license: meta.license ?? null, verified: !!meta.verified, namespaceAccess: meta.namespaceAccess ?? null,
      publishedBy: meta.publishedBy ? { loginName: meta.publishedBy.loginName, provider: meta.publishedBy.provider } : null,
      deprecated: !!meta.deprecated, registryEngines: meta.engines || null, registryExtensionKind: meta.extensionKind || null,
      targetsAvailable: targets, defaultTarget: meta.targetPlatform, targetChosen: choice.target, targetReason: choice.reason,
      noGlibcArm64: choice.noGlibcArm64,
      files: Object.fromEntries(['download', 'sha256', 'signature', 'manifest', 'readme', 'changelog', 'license', 'vsixmanifest']
        .filter((k) => files[k]).map((k) => [k, files[k]])),
      signatureAvailable: !!files.signature,
    };
    if (!NO_DOWNLOAD && files.download) {
      const vsix = path.join(VSIX_DIR, `${id}-${meta.version}@${choice.target}.vsix`);
      try {
        const d = await download(files.download, vsix);
        rec.vsixPath = vsix; rec.vsixBytes = d.bytes; rec.vsixSha256 = d.sha256;
        if (files.sha256) {
          const s = await getCached(files.sha256);
          const expected = s.status === 200 ? (s.body.trim().split(/\s+/)[0] || '').toLowerCase() : null;
          rec.sha256Expected = expected;
          rec.sha256Verified = expected ? expected === d.sha256 : null;
        } else rec.sha256Verified = null;
        const xdir = path.join(X_DIR, `${id}-${meta.version}-${choice.target}`);
        if (!fs.existsSync(path.join(xdir, '.complete'))) {
          fs.rmSync(xdir, { recursive: true, force: true });
          fs.mkdirSync(xdir, { recursive: true });
          const u = spawnSync('unzip', ['-q', '-o', vsix, '-d', xdir], { stdio: ['ignore', 'ignore', 'pipe'] });
          if (u.status !== 0 && u.status !== 1) throw new Error('unzip failed: ' + u.stderr);
          fs.writeFileSync(path.join(xdir, '.complete'), '');
        }
        rec.unpackedDir = xdir;
      } catch (e) {
        rec.downloadError = String(e.message || e);
      }
    }
    done++;
    if (done % 10 === 0 || done === list.length) console.log(`downloaded ${done}/${list.length}`);
    return rec;
  })));

  const state = {
    meta: {
      snapshotAt: snap.snapshotAt, generatedAt: new Date().toISOString(), size: SIZE,
      searchPages, codeInTop: codeCount, declarativeInTop: declarative.length,
      mustWork: MUST_WORK,
    },
    declarative,
    unresolved: [...byId.values()].filter((r) => r.unresolved).map((r) => ({ id: r.id, mustWork: r.mustWork, dependencyOf: r.dependencyOf })),
    extensions: out,
  };
  fs.writeFileSync(path.join(CACHE, 'fetch-state.json'), JSON.stringify(state, null, 1));
  const bytes = out.reduce((a, r) => a + (r.vsixBytes || 0), 0);
  console.log(`extensions=${out.length} declarative=${declarative.length} vsixBytes=${bytes} errors=${out.filter((r) => r.downloadError).length}`);
}

main().catch((e) => { console.error(e); process.exit(1); });
