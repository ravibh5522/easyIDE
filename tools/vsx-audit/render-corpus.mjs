#!/usr/bin/env node
// render-corpus.mjs - regenerate the data-derived tables in docs/vsx-compat/corpus.md and
//   docs/vsx-compat/corpus-profiles.md from docs/vsx-compat/data/{corpus,corpus-usage}.json.
// Zero dependencies (node:fs only). Idempotent: only the text between each
//   <!-- gen:start NAME --> / <!-- gen:end NAME --> marker pair is replaced; everything else
//   (prose, headings outside markers) is left untouched byte-for-byte.
// Usage: node tools/vsx-audit/render-corpus.mjs
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const DATA_DIR = path.resolve(HERE, '../../docs/vsx-compat/data');
const DOC_DIR = path.resolve(HERE, '../../docs/vsx-compat');
const CORPUS_MD = path.join(DOC_DIR, 'corpus.md');
const PROFILES_MD = path.join(DOC_DIR, 'corpus-profiles.md');

const corpus = JSON.parse(fs.readFileSync(path.join(DATA_DIR, 'corpus.json'), 'utf8'));
const usage = JSON.parse(fs.readFileSync(path.join(DATA_DIR, 'corpus-usage.json'), 'utf8'));

const TOTAL_DL = corpus.meta.counts.totalDownloads;
const pct = (n, d = TOTAL_DL) => (100 * n / d).toFixed(2) + '%';
const fmtInt = (n) => n.toLocaleString('en-US');
const esc = (s) => String(s ?? '').replace(/\|/g, '\\|').replace(/\n/g, ' ');
const trunc = (s, n) => { s = String(s ?? ''); return s.length > n ? s.slice(0, n - 1) + '…' : s; };
const vsxUrl = (id) => { const i = id.indexOf('.'); return `https://open-vsx.org/extension/${id.slice(0, i)}/${id.slice(i + 1)}`; };

// ---------- classification (D7 in-scope rule, D8 OOS tracks) ----------
const NB_ONLY_KEYS = new Set(['notebookRenderer', 'notebookPreload', 'notebooks']);
const CHAT_KEYS = new Set(['chatParticipants', 'languageModelTools', 'chatSkills', 'chatInstructions', 'chatContext', 'mcpServerDefinitionProviders', 'languageModelToolSets']);
const CHAT_API_RE = /^(lm\.|chat\.|LanguageModel|Chat[A-Z])/;

function oosFlags(e) {
  const contribKeys = Object.keys(e.contributes || {});
  const dap = (e.debuggers || 0) > 0;
  const nb = (e.notebooks || 0) > 0 || (e.contributes?.notebookRenderer || 0) > 0 || (e.contributes?.notebookPreload || 0) > 0;
  const chat = contribKeys.some((k) => CHAT_KEYS.has(k)) || Object.keys(e.apiUsage || {}).some((k) => CHAT_API_RE.test(k));
  return { dap, nb, chat };
}

function classify(e) {
  if (e.noGlibcArm64) return { cls: 'platform-unavailable', reason: `no linux-arm64/universal build published; falls back to ${e.targetChosen}` };
  const views = e.views || {};
  const extraViewKeys = Object.keys(views).filter((k) => k !== 'webviewViews');
  const debuggerOnly = (e.debuggers || 0) > 0 && (e.languages || 0) === 0 && extraViewKeys.length === 0 && (views.webviewViews || 0) === 0 && (e.notebooks || 0) === 0;
  if (debuggerOnly) return { cls: 'oos:DAP', reason: `debug-adapter-only: contributes.debuggers=${e.debuggers}, no languages/views contributions` };
  const contribKeys = Object.keys(e.contributes || {});
  if (contribKeys.length > 0 && contribKeys.every((k) => NB_ONLY_KEYS.has(k))) {
    return { cls: 'oos:NB', reason: `notebook-renderer-only contribution points (${contribKeys.join(', ')})` };
  }
  if (contribKeys.length > 0 && contribKeys.every((k) => CHAT_KEYS.has(k))) {
    return { cls: 'oos:CHAT', reason: `chat/lm-only contribution points (${contribKeys.join(', ')})` };
  }
  return { cls: 'in', reason: '' };
}

const rows = corpus.extensions.map((e) => ({ e, oos: oosFlags(e), cls: classify(e) }));
rows.sort((a, b) => b.e.downloadCount - a.e.downloadCount);

// ---------- section 2: full table of all 156 ----------
function nativeCell(e) {
  const nb = e.nativeBinaries || [];
  if (nb.length === 0) return '-';
  const glibcArm = nb.some((n) => n.arch === 'aarch64' && n.libc === 'glibc');
  const staticArm = nb.some((n) => n.arch === 'aarch64' && n.libc === 'static/none');
  const ok = glibcArm || staticArm ? 'Y' : 'N';
  return `${nb.length} (${ok})`;
}

function oosCell(oos) {
  const parts = [];
  if (oos.dap) parts.push('DAP');
  if (oos.nb) parts.push('NB');
  if (oos.chat) parts.push('CHAT');
  return parts.length ? parts.join('/') : '-';
}

function renderFullTable() {
  const head = '| rank | id | version | downloads | weight% | licence | target | native(#,ok) | engines.vscode | #prop | OOS | class |\n' +
    '|---|---|---|---|---|---|---|---|---|---|---|---|\n';
  const body = rows.map(({ e, oos, cls }) => {
    const rank = e.rank == null ? `dep` : e.rank;
    const idCell = `[${esc(e.id)}](${vsxUrl(e.id)})`;
    return `| ${rank} | ${idCell} | ${esc(e.version)} | ${fmtInt(e.downloadCount)} | ${pct(e.downloadCount)} | ${esc(trunc(e.license ?? '?', 34))} | ${e.targetChosen} | ${nativeCell(e)} | ${esc(e.engines?.vscode ?? '-')} | ${(e.enabledApiProposals || []).length} | ${oosCell(oos)} | ${cls.cls} |`;
  }).join('\n');
  return head + body + '\n';
}

function renderExclusions() {
  const excluded = rows.filter((r) => r.cls.cls !== 'in');
  const byCls = {};
  for (const r of excluded) (byCls[r.cls.cls] ??= []).push(r);
  const order = ['platform-unavailable', 'oos:DAP', 'oos:NB', 'oos:CHAT'];
  let out = '| class | id | downloads | weight% | reason |\n|---|---|---|---|---|\n';
  let totalW = 0;
  for (const cls of order) {
    for (const r of (byCls[cls] || []).sort((a, b) => b.e.downloadCount - a.e.downloadCount)) {
      totalW += r.e.downloadCount;
      out += `| ${cls} | ${esc(r.e.id)} | ${fmtInt(r.e.downloadCount)} | ${pct(r.e.downloadCount)} | ${esc(r.cls.reason)} |\n`;
    }
  }
  const inCount = rows.length - excluded.length;
  const inW = TOTAL_DL - totalW;
  out += `\nE_in = ${inCount} extensions, ${fmtInt(inW)} downloads (${pct(inW)} of corpus weight). Excluded: ${excluded.length} extensions, ${fmtInt(totalW)} downloads (${pct(totalW)}).\n`;
  return out;
}

// ---------- section 3: aggregates ----------
function renderTargetDist() {
  const m = {};
  for (const { e } of rows) m[e.targetChosen] = (m[e.targetChosen] || { n: 0, w: 0 });
  for (const { e } of rows) { m[e.targetChosen].n++; m[e.targetChosen].w += e.downloadCount; }
  let out = '| target | #ext | weight% |\n|---|---|---|\n';
  for (const [t, v] of Object.entries(m).sort((a, b) => b[1].w - a[1].w)) out += `| ${t} | ${v.n} | ${pct(v.w)} |\n`;
  return out;
}

function enginesLowerBound(s) {
  if (!s || s === '*') return 0;
  const m = s.match(/(\d+)\.(\d+)/);
  if (!m) return 0;
  return Number(m[1]) * 1000 + Number(m[2]);
}

function renderEnginesDist() {
  const buckets = [
    ['<=1.60', (v) => v <= 1060],
    ['1.61-1.79', (v) => v > 1060 && v <= 1079],
    ['1.80-1.89', (v) => v >= 1080 && v <= 1089],
    ['1.90-1.99', (v) => v >= 1090 && v <= 1099],
    ['1.100-1.105', (v) => v >= 1100 && v <= 1105],
    ['1.106-1.114', (v) => v >= 1106 && v <= 1114],
    ['1.115-1.136', (v) => v >= 1115 && v <= 1136],
    ['1.137+', (v) => v >= 1137],
  ];
  const counts = buckets.map(() => 0);
  let max = 0; let maxId = '';
  for (const { e } of rows) {
    const v = enginesLowerBound(e.engines?.vscode);
    if (v > max) { max = v; maxId = e.id; }
    for (let i = 0; i < buckets.length; i++) if (buckets[i][1](v)) { counts[i]++; break; }
  }
  let out = '| engines.vscode lower bound | #ext |\n|---|---|\n';
  buckets.forEach(([label], i) => { out += `| ${label} | ${counts[i]} |\n`; });
  const top5 = [...rows].sort((a, b) => enginesLowerBound(b.e.engines?.vscode) - enginesLowerBound(a.e.engines?.vscode)).slice(0, 5);
  out += `\nHighest engines.vscode requirement: **${maxId}** (\`${rows.find((r) => r.e.id === maxId).e.engines.vscode}\`). Top 5: ` +
    top5.map((r) => `${r.e.id} \`${r.e.engines?.vscode}\``).join(', ') + '.\n';
  return out;
}

function renderNativeSummary() {
  const withNative = rows.filter((r) => (r.e.nativeBinaries || []).length > 0);
  const usable = withNative.filter((r) => nativeCell(r.e).includes('(Y)'));
  const notUsable = withNative.filter((r) => nativeCell(r.e).includes('(N)'));
  const wAll = withNative.reduce((s, r) => s + r.e.downloadCount, 0);
  const wUsable = usable.reduce((s, r) => s + r.e.downloadCount, 0);
  const wNot = notUsable.reduce((s, r) => s + r.e.downloadCount, 0);
  let out = `Ships native binaries: **${withNative.length}** extensions, ${pct(wAll)} weight. Of those, **${usable.length}** ` +
    `(${pct(wUsable)}) have at least one aarch64 binary that is glibc-dynamic or statically linked (runnable as-is in the ` +
    `proot Ubuntu arm64/glibc sandbox); **${notUsable.length}** (${pct(wNot)}) do not.\n\n`;
  out += '| id | downloads | weight% | native binaries |\n|---|---|---|---|\n';
  for (const r of notUsable.sort((a, b) => b.e.downloadCount - a.e.downloadCount)) {
    const grouped = {};
    for (const n of r.e.nativeBinaries || []) {
      const key = `${n.format ?? n.kind}/${n.arch ?? 'noarch'}${n.libc ? '/' + n.libc : ''}`;
      grouped[key] = (grouped[key] || 0) + 1;
    }
    const kinds = Object.entries(grouped).map(([k, n]) => (n > 1 ? `${k}×${n}` : k)).join(', ');
    out += `| ${esc(r.e.id)} | ${fmtInt(r.e.downloadCount)} | ${pct(r.e.downloadCount)} | ${esc(kinds)} |\n`;
  }
  return out;
}

function renderUsageTable(obj, { top = null, filter = null } = {}) {
  let entries = Object.entries(obj);
  if (filter) entries = entries.filter(([k]) => filter(k));
  entries.sort((a, b) => b[1].weightedDownloads - a[1].weightedDownloads);
  if (top) entries = entries.slice(0, top);
  let out = '| key | #ext | call sites | weight% |\n|---|---|---|---|\n';
  for (const [k, v] of entries) out += `| ${esc(k)} | ${v.count} | ${fmtInt(v.callSites)} | ${pct(v.weightedDownloads)} |\n`;
  return out;
}

function renderChildProcessCallout() {
  const v = usage.nodeBuiltins.child_process;
  if (!v) return '_no extension imports child_process_\n';
  return `\`child_process\` is imported by **${v.count}** extensions (${fmtInt(v.callSites)} import sites), ${pct(v.weightedDownloads)} of corpus weight - the single largest node-builtin surface (LSP servers, CLIs, git spawn process.spawn/exec).\n`;
}

// ---------- assemble corpus.md ----------
const GEN = {
  snapshot_counts: () => {
    const c = corpus.meta.counts;
    return `| item | value |\n|---|---|\n` +
      `| total extensions in corpus | ${c.extensions} (${c.top150Code} top code + ${c.mustWork} must-work − overlap + ${c.dependencyOnly} dependency-only) |\n` +
      `| top code extensions | ${c.top150Code} |\n| must-work ids | ${c.mustWork} |\n| dependency-only members | ${c.dependencyOnly} |\n` +
      `| declarative in rank range (excluded from the 150) | ${c.declarativeInTopRange}, ${fmtInt(corpus.declarative.reduce((s, d) => s + d.downloadCount, 0))} downloads |\n` +
      `| total download weight (all 156) | ${fmtInt(c.totalDownloads)} |\n| total .vsix bytes downloaded | ${fmtInt(c.totalVsixBytes)} (${(c.totalVsixBytes / 1e9).toFixed(2)} GB) |\n` +
      `| total unpacked bytes | ${fmtInt(c.totalUnpackedBytes)} |\n`;
  },
  full_table: renderFullTable,
  exclusions: renderExclusions,
  target_dist: renderTargetDist,
  engines_dist: renderEnginesDist,
  native_summary: renderNativeSummary,
  proposals_top: () => renderUsageTable(usage.proposals, { top: 12 }),
  node_builtins: () => renderUsageTable(usage.nodeBuiltins, { top: 12 }),
  child_process: renderChildProcessCallout,
  activation_events: () => renderUsageTable(usage.activationEvents, { top: 12 }),
  implicit_activation_events: () => renderUsageTable(usage.implicitActivationEvents, { top: 8 }),
  contributes_top: () => renderUsageTable(usage.contributes, { top: 30 }),
  menus_top: () => renderUsageTable(usage.menus, { top: 20 }),
  when_keys_top: () => renderUsageTable(usage.whenKeys, { top: 30 }),
};

function applyMarkers(filePath, gen) {
  let text = fs.readFileSync(filePath, 'utf8');
  let count = 0;
  text = text.replace(/(<!--\s*gen:start\s+([\w-]+)\s*-->)([\s\S]*?)(<!--\s*gen:end\s+\2\s*-->)/g, (whole, startTag, name, _body, endTag) => {
    if (!(name in gen)) { console.warn(`render-corpus: no generator for marker "${name}" in ${filePath}`); return whole; }
    count++;
    const content = gen[name]();
    return `${startTag}\n${content.trimEnd()}\n${endTag}`;
  });
  fs.writeFileSync(filePath, text);
  console.log(`render-corpus: ${path.relative(process.cwd(), filePath)} - filled ${count} marker(s)`);
}

// ---------- section 5: must-work profiles (corpus-profiles.md) ----------
function apiTop10(e) {
  return Object.entries(e.apiUsage || {}).sort((a, b) => b[1] - a[1]).slice(0, 10).map(([k, v]) => `${k}(${v})`).join(', ');
}
function contributesLine(e) {
  return Object.entries(e.contributes || {}).sort((a, b) => b[1] - a[1]).map(([k, v]) => `${k}:${v}`).join(', ') || '(none)';
}
const SPIKE_ACTIVATED = new Set(['eamodio.gitlens', 'Anthropic.claude-code', 'golang.Go', 'dbaeumer.vscode-eslint', 'esbenp.prettier-vscode']);
function spikeStatus(id) {
  if (SPIKE_ACTIVATED.has(id)) return 'activated unmodified in the route-spike (research/route-spike.md §3)';
  return 'not run in the spike (only 8 extensions were spiked); expected to load per D1 evidence, UNVERIFIED for this id';
}
function profileBlock(e) {
  const { dap, nb, chat } = oosFlags(e);
  const oosParts = [dap && 'DAP (contributes.debuggers)', nb && 'NB (notebook contributions)', chat && 'CHAT (chat/lm API or contributions)'].filter(Boolean).join('; ') || 'none';
  const nat = (e.nativeBinaries || []).length ? `${e.nativeBinaries.length} (${nativeCell(e)})` : '0';
  return `### ${e.id} ${e.version} (${fmtInt(e.downloadCount)} dl, rank ${e.rank ?? 'dep'})\n` +
    `- licence: ${esc(e.license ?? '?')} | target: ${e.targetChosen} | engines.vscode: ${esc(e.engines?.vscode ?? '-')}\n` +
    `- activation: ${JSON.stringify(e.activationEvents)} (+${(e.implicitActivationFromContributes || []).length} implicit)\n` +
    `- contributes: ${esc(contributesLine(e))}\n` +
    `- top 10 APIs by call sites: ${esc(apiTop10(e)) || '(none)'}\n` +
    `- native binaries: ${nat} | proposals: ${(e.enabledApiProposals || []).join(', ') || '-'}\n` +
    `- OOS parts: ${oosParts}\n` +
    `- blockers: ${esc((e.blockers || []).join('; ')) || '-'}\n` +
    `- spike status: ${spikeStatus(e.id)}\n`;
}

function renderMustWorkProfiles() {
  const ids = corpus.meta.mustWorkList;
  let out = '';
  for (const id of ids) {
    const e = corpus.extensions.find((x) => x.id === id);
    if (!e) { out += `### ${id}\nNOT FOUND in corpus.json (UNVERIFIED)\n\n`; continue; }
    out += profileBlock(e) + '\n';
  }
  return out;
}

const GEN_PROFILES = { must_work_profiles: renderMustWorkProfiles };

// ---------- run ----------
if (fs.existsSync(CORPUS_MD)) applyMarkers(CORPUS_MD, GEN);
else console.warn(`render-corpus: ${CORPUS_MD} does not exist yet; write it with marker pairs first`);
if (fs.existsSync(PROFILES_MD)) applyMarkers(PROFILES_MD, GEN_PROFILES);
else console.warn(`render-corpus: ${PROFILES_MD} does not exist yet; write it with marker pairs first`);
