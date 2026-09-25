#!/usr/bin/env node
// Generates docs/vsx-compat/agent-briefs/<WP>.md (one implementation prompt per work package) and the initial
// docs/vsx-compat/tracker.md from the ordered table in docs/vsx-compat/roadmap.md (section 2) and
// tools/vsx-audit/mapping/capabilities.json. Existing files are kept unless --force: after the first run the
// tracker is edited by hand by implementation agents (it is the live tracker).
// Usage: node tools/vsx-audit/gen-tracker-briefs.mjs [--force]
import fs from 'node:fs';
import path from 'node:path';

const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), '../..');
const docs = path.join(root, 'docs/vsx-compat');
const force = process.argv.includes('--force');
const roadmap = fs.readFileSync(path.join(docs, 'roadmap.md'), 'utf8');
const sec = roadmap.slice(roadmap.indexOf('## 2.'), roadmap.indexOf('## 3.'));
const rows = sec.split('\n').filter(l => /^\| \d+ \| WP-/.test(l)).map(l => {
  const c = l.split('|').slice(1, -1).map(s => s.trim());
  return { n: c[0], id: c[1], title: c[2], m: c[3], deps: c[4], effort: c[5], risk: c[6], owner: c[7], accept: c[9] };
});
if (!rows.length) throw new Error('no work-package rows found in roadmap.md section 2');

const docFor = id => {
  const p = id.split('-')[1];
  return {
    HOST: '[backend.md](../backend.md), [backend-2.md](../backend-2.md), [design.md](../design.md), [design-protocol.md](../design-protocol.md)',
    API: '[backend.md](../backend.md), [backend-2.md](../backend-2.md), [design.md](../design.md) section 6, [design-protocol.md](../design-protocol.md)',
    UI: '[ui.md](../ui.md), [ui-webviews.md](../ui-webviews.md), [design-protocol.md](../design-protocol.md)',
    REG: '[registry-install.md](../registry-install.md)',
    SEC: '[security-licensing.md](../security-licensing.md), [licensing-policy.md](../licensing-policy.md)',
    PERF: '[optimisation.md](../optimisation.md)',
    TEST: '[test-program.md](../test-program.md), [corpus.md](../corpus.md)',
    OOS: '[roadmap-oos.md](../roadmap-oos.md)',
  }[p] || '[roadmap.md](../roadmap.md)';
};

const brief = r => `# ${r.id} - ${r.title}

Generated from [roadmap.md](../roadmap.md) row ${r.n} by \`tools/vsx-audit/gen-tracker-briefs.mjs\`. Ready-to-run prompt for an
autonomous implementation agent. Milestone ${r.m}. Effort ${r.effort}. Risk ${r.risk}. Owner role: ${r.owner}.

## Prompt

You are the **${r.owner}** agent implementing **${r.id} (${r.title})** for easyIDE's VS Code extension compatibility
program. Work in your own git worktree on a branch named \`wp/${r.id.toLowerCase()}\`, one work package per branch.

1. Read first: \`.claude/CLAUDE.md\` and [CONTRIBUTING.md](../../../CONTRIBUTING.md) (600-line file limit, no hardcoding,
   errors only at real boundaries, chainlog entry), [README.md](../README.md), ${docFor(r.id)}, the ADRs
   [0031](../../decision/0031-vendor-vscode-extension-host.md), [0032](../../decision/0032-node-runtime-provisioning.md),
   [0033](../../decision/0033-extension-webview-security-model.md), [0034](../../decision/0034-play-policy-stance-code-extensions.md),
   and the matrix rows that name ${r.id} in [matrix/](../matrix/).
2. Dependencies that must be Done in [tracker.md](../tracker.md) before you start: ${r.deps === '-' ? 'none' : r.deps.replace(/\b([A-Z]+-\d+)/g, 'WP-$1')}.
3. Scope: exactly what the source docs above assign to ${r.id}; anything else goes to the lead as a note, not into this branch.
   Respect the ownership boundaries and merge order in [roadmap.md](../roadmap.md) section 4.
4. Acceptance (must be demonstrated, with the command output or device evidence in the PR): ${r.accept}
5. Verify with the builds and tests that exist (\`:app:testDebugUnitTest\`, module tests, goldens, the harness in
   \`tools/vsx-audit/\`); anything you could not run (no device) is stated as not verified.
6. In the same PR: set ${r.id} to Done (or In progress with a note) in [tracker.md](../tracker.md), update the matrix rows it
   closes (\`tools/vsx-audit/mapping/*.json\`, then re-run \`tools/vsx-audit/build-matrix.mjs\`), and append a chainlog entry.
7. Never describe proot as isolation; never bundle third-party extensions; never add telemetry.
`;

fs.mkdirSync(path.join(docs, 'agent-briefs'), { recursive: true });
let wrote = 0;
for (const r of rows) {
  const f = path.join(docs, 'agent-briefs', `${r.id}.md`);
  if (force || !fs.existsSync(f)) { fs.writeFileSync(f, brief(r)); wrote++; }
}

const trackerPath = path.join(docs, 'tracker.md');
if (force || !fs.existsSync(trackerPath)) {
  const capsPath = path.join(root, 'tools/vsx-audit/mapping/capabilities.json');
  const caps = fs.existsSync(capsPath) ? JSON.parse(fs.readFileSync(capsPath, 'utf8')) : {};
  const capRows = Object.entries(caps.capabilities || caps).filter(([k, v]) => v && typeof v === 'object' && v.gap)
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([k, v]) => `| \`${k}\` | ${v.gap} | ${v.milestone || ''} | ${(v.wp || []).join(', ')} | Not started |`);
  const out = [
    '# VS Code extension compatibility - live tracker',
    '',
    'Status per work package and per capability (matrix row group). Implementation agents update their row in the same PR',
    'as the work (Not started / In progress / Done, with a link to the PR or commit). Plan: [roadmap.md](roadmap.md).',
    'Generated once by `tools/vsx-audit/gen-tracker-briefs.mjs`; edited by hand since. Score history: `results/` (see',
    '[test-program.md](test-program.md)); current usage-weighted score S_in: not measured (no host exists yet).',
    '',
    '## Work packages',
    '',
    '| # | WP | Title | M | Deps | Effort | Owner | Brief | Status | Evidence |',
    '|---|---|---|---|---|---|---|---|---|---|',
    ...rows.map(r => `| ${r.n} | ${r.id} | ${r.title} | ${r.m} | ${r.deps} | ${r.effort} | ${r.owner} | [brief](agent-briefs/${r.id}.md) | Not started | |`),
    '',
    '## Capabilities (matrix rows grouped by capability id; row detail in [matrix/](matrix/))',
    '',
    capRows.length ? '| Capability | Gap today | M | WP | Status |\n|---|---|---|---|---|\n' + capRows.join('\n')
      : 'Pending: capability mapping (`tools/vsx-audit/mapping/capabilities.json`) not generated yet; re-run with --force.',
    '',
  ].join('\n');
  fs.writeFileSync(trackerPath, out);
}
console.log(`work packages: ${rows.length}, briefs written: ${wrote}`);
