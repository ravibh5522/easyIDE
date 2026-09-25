# VS Code extension corpus (Open VSX, 2026-09-25)

Companion: [corpus-profiles.md](corpus-profiles.md) (5-10 line profile per must-work extension). Raw data:
[data/corpus.json](data/corpus.json), [data/corpus-usage.json](data/corpus-usage.json). Research notes (primary source
for everything below, with extra detail and spot-checks): [research/corpus-notes.md](research/corpus-notes.md).
D7/D8 (score formula, in-scope rule, OOS tracks) are binding and re-stated, not re-decided, here.

## 1. Snapshot and method

- **Snapshot time**: 2026-09-25T14:49:49.567Z (first Open VSX search request).
- **Sources**: search `https://open-vsx.org/api/-/search?sortBy=downloadCount&sortOrder=desc&size=100&offset={0,100}`
  (`totalSize` 18401 published extensions on Open VSX at snapshot time); per-extension detail from
  `https://open-vsx.org/api/{namespace}/{name}` and `.../{target}/{version}`.
- **How the list was built** (`tools/vsx-audit/corpus-fetch.mjs`): walk the search results ranked by `downloadCount`
  descending; keep an extension as a "top code" entry once its `package.json` has a `main` or `browser` field (i.e. it
  runs code, is not purely declarative — grammar/theme/snippets/keymap/language-pack); stop once 150 such extensions are
  collected (ranks 1..163 were walked to get there — 13 ranks in that range are declarative and are not code
  extensions). Separately resolve all 22 "must-work" ids named in [test-program.md](test-program.md) and D7, and walk
  each one's `extensionDependencies` + `extensionPack` transitively, adding any member not already in the top 150.
  Target platform chosen per extension: `linux-arm64` if published, else `universal`, else the first other platform
  published (flagged `noGlibcArm64: true`, kept for analysis only, never installable on-device as-is). Every `.vsix` was
  downloaded and its SHA-256 verified against the registry's own `.sha256` file.
- **Composition** (see the generated counts table below): 150 top code + 22 must-work (21 already inside the top 150;
  the 22nd, `ms-azuretools.vscode-docker`, is a rank-34 **declarative** stub — see `## 5`) + 5 dependency-only
  pack/dependency members not otherwise in the top 150 (`vscode.git`, `vscode.git-base`,
  `vscode.github-authentication`, `vscode.docker`, `vscode.yaml`) = **156**. All 22 must-work ids resolved; the
  lowest-ranked is `usernamehw.errorlens` at rank 102. SHA-256 verified 156/156; `.sigzip` publisher signature present
  for 156/156 (not cryptographically verified — out of scope for this audit).
- **13 declarative extensions skipped** from the top-150 code walk (ranks 1..163, kept in `corpus.json` `declarative[]`
  for the record, not scored): `magicstack.MagicPython` (grammar, 11,793,065 dl), `EricSia.pythonsnippets3` (snippets,
  11,742,319), `ms-toolsai.jupyter-keymap` (keymap, 8,671,013), the 5 `MS-CEINTL.vscode-language-pack-*`
  (zh-hans/ja/es/pt-BR/ko, 3,610,314 + 1,447,809 + 829,582 + 599,494 + 591,168), `GraphQL.vscode-graphql-syntax` (grammar,
  952,321), `wingrunr21.vscode-ruby` (grammar, 929,758), `vscode.json` (grammar, 637,783),
  `ms-vscode.vscode-typescript-next` (grammar, 575,826). Note: `PKief.material-icon-theme` (rank 37) looked declarative
  (icon theme) but has a real `main` and is correctly a code extension — see `## 6`.
- **Unresolved**: `ms-python.vscode-pylance` (an `extensionPack` member of `ms-python.python`, not must-work itself) —
  `https://open-vsx.org/api/ms-python/vscode-pylance` returns HTTP 404 `{"error":"Extension not found: ms-python.vscode-pylance"}`.
  Pack members are optional, so `ms-python.python` still installs without it. Treated as UNVERIFIED-absent, not counted
  in the 156.

<!-- gen:start snapshot_counts -->
| item | value |
|---|---|
| total extensions in corpus | 156 (150 top code + 22 must-work − overlap + 5 dependency-only) |
| top code extensions | 150 |
| must-work ids | 22 |
| dependency-only members | 5 |
| declarative in rank range (excluded from the 150) | 13, 49,195,363 downloads |
| total download weight (all 156) | 1,091,356,814 |
| total .vsix bytes downloaded | 2,729,485,852 (2.73 GB) |
| total unpacked bytes | 6,829,271,295 |
<!-- gen:end snapshot_counts -->

## 2. Full corpus table (all 156, generated)

Columns: `native(#,ok)` = native binary count and whether at least one is aarch64 glibc-dynamic or static (runnable
as-is in the proot Ubuntu arm64/glibc sandbox, `Y`/`N`); `#prop` = count of `enabledApiProposals`; `OOS` = which D8
out-of-scope tracks this extension **contributes to or uses** regardless of its own classification (`DAP` = contributes
one or more debuggers, `NB` = notebook contribution points, `CHAT` = chat/`lm.*` API use or chat/LM/MCP contribution
points); `class` = the D7 in-scope classification below. Sorted by `downloadCount` descending; `rank` is the Open VSX
search rank (`dep` = a dependency/pack member outside the ranked walk, ordered here by its own download count).

<!-- gen:start full_table -->
| rank | id | version | downloads | weight% | licence | target | native(#,ok) | engines.vscode | #prop | OOS | class |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | [meta.pyrefly](https://open-vsx.org/extension/meta/pyrefly) | 1.3.9001 | 86,563,040 | 7.93% | MIT | linux-arm64 | 1 (Y) | ^1.103.0 | 1 | - | in |
| 2 | [ms-python.debugpy](https://open-vsx.org/extension/ms-python/debugpy) | 2026.6.0 | 60,064,855 | 5.50% | MIT | linux-arm64 | 2 (N) | ^1.92.0 | 3 | DAP | oos:DAP |
| 3 | [ms-python.python](https://open-vsx.org/extension/ms-python/python) | 2026.4.0 | 59,121,980 | 5.42% | MIT | universal | - | ^1.95.0 | 9 | DAP/CHAT | in |
| 4 | [Anthropic.claude-code](https://open-vsx.org/extension/Anthropic/claude-code) | 2.1.282 | 52,395,010 | 4.80% | © Anthropic PBC. All rights reser… | linux-arm64 | 2 (Y) | ^1.94.0 | 0 | - | in |
| 5 | [Shopify.ruby-lsp](https://open-vsx.org/extension/Shopify/ruby-lsp) | 0.10.6 | 43,132,270 | 3.95% | MIT | universal | - | ^1.91.0 | 0 | DAP/CHAT | in |
| 6 | [redhat.java](https://open-vsx.org/extension/redhat/java) | 1.57.2026092508 | 42,743,388 | 3.92% | EPL-2.0 | linux-arm64 | 66 (Y) | ^1.77.0 | 0 | - | in |
| 7 | [ms-python.vscode-python-envs](https://open-vsx.org/extension/ms-python/vscode-python-envs) | 1.38.0 | 41,913,485 | 3.84% | ? | universal | - | ^1.110.0-20260204 | 3 | - | in |
| 8 | [golang.Go](https://open-vsx.org/extension/golang/Go) | 0.56.1 | 40,903,961 | 3.75% | MIT | universal | - | ^1.90.0 | 0 | DAP | in |
| 9 | [devsense.composer-php-vscode](https://open-vsx.org/extension/devsense/composer-php-vscode) | 1.74.19317 | 40,878,749 | 3.75% | LicenseRef-LICENSE | universal | - | ^1.63.1 | 0 | - | in |
| 10 | [devsense.profiler-php-vscode](https://open-vsx.org/extension/devsense/profiler-php-vscode) | 1.74.19317 | 40,845,634 | 3.74% | LicenseRef-LICENSE | universal | - | ^1.63.1 | 0 | - | in |
| 11 | [devsense.phptools-vscode](https://open-vsx.org/extension/devsense/phptools-vscode) | 1.74.19317 | 39,894,154 | 3.66% | LicenseRef-LICENSE | linux-arm64 | 3 (Y) | ^1.70.0 | 0 | DAP/CHAT | in |
| 12 | [vscjava.vscode-java-dependency](https://open-vsx.org/extension/vscjava/vscode-java-dependency) | 0.27.6 | 39,653,408 | 3.63% | MIT | universal | - | ^1.95.0 | 0 | CHAT | in |
| 13 | [llvm-vs-code-extensions.vscode-clangd](https://open-vsx.org/extension/llvm-vs-code-extensions/vscode-clangd) | 0.6.0 | 30,200,038 | 2.77% | MIT | universal | - | ^1.75.0 | 0 | - | in |
| 14 | [vscjava.vscode-maven](https://open-vsx.org/extension/vscjava/vscode-maven) | 0.45.3 | 28,170,574 | 2.58% | MIT | universal | - | ^1.75.0 | 0 | - | in |
| 15 | [vscjava.vscode-gradle](https://open-vsx.org/extension/vscjava/vscode-gradle) | 3.18.0 | 27,225,448 | 2.49% | SEE LICENSE IN LICENSE.md | universal | - | ^1.76.0 | 0 | - | in |
| 16 | [vscjava.vscode-java-debug](https://open-vsx.org/extension/vscjava/vscode-java-debug) | 0.59.0 | 26,023,408 | 2.38% | SEE LICENSE IN LICENSE.txt | universal | - | ^1.95.0 | 0 | DAP/CHAT | oos:DAP |
| 17 | [vscjava.vscode-java-test](https://open-vsx.org/extension/vscjava/vscode-java-test) | 0.46.0 | 25,821,235 | 2.37% | ? | universal | - | ^1.88.0 | 0 | - | in |
| 18 | [vscjava.vscode-java-pack](https://open-vsx.org/extension/vscjava/vscode-java-pack) | 0.31.1 | 21,954,832 | 2.01% | MIT | universal | - | ^1.74.0 | 0 | - | in |
| 19 | [eamodio.gitlens](https://open-vsx.org/extension/eamodio/gitlens) | 2026.9.250515 | 16,867,728 | 1.55% | %gitlens.license% | universal | - | ^1.101.0 | 0 | CHAT | in |
| 20 | [ms-azuretools.vscode-containers](https://open-vsx.org/extension/ms-azuretools/vscode-containers) | 2.4.5 | 14,319,357 | 1.31% | SEE LICENSE IN LICENSE.md | universal | - | ^1.105.0 | 0 | DAP/CHAT | in |
| 21 | [openai.chatgpt](https://open-vsx.org/extension/openai/chatgpt) | 26.5908.31748 | 12,034,237 | 1.10% | SEE LICENSE IN LICENSE.md | linux-arm64 | 6 (Y) | ^1.96.2 | 2 | CHAT | in |
| 24 | [ms-toolsai.jupyter](https://open-vsx.org/extension/ms-toolsai/jupyter) | 2025.9.1 | 10,336,000 | 0.95% | MIT | universal | 16 (Y) | ^1.105.0 | 13 | DAP/NB/CHAT | in |
| 25 | [Vue.volar](https://open-vsx.org/extension/Vue/volar) | 3.3.11 | 10,191,124 | 0.93% | ? | universal | - | ^1.88.0 | 0 | - | in |
| 26 | [ms-toolsai.jupyter-renderers](https://open-vsx.org/extension/ms-toolsai/jupyter-renderers) | 1.3.0 | 9,755,102 | 0.89% | MIT | universal | - | ^1.95.0 | 1 | NB | oos:NB |
| 27 | [esbenp.prettier-vscode](https://open-vsx.org/extension/esbenp/prettier-vscode) | 12.4.0 | 9,225,853 | 0.85% | MIT | universal | - | ^1.101.0 | 0 | - | in |
| 29 | [ms-toolsai.vscode-jupyter-cell-tags](https://open-vsx.org/extension/ms-toolsai/vscode-jupyter-cell-tags) | 0.1.9 | 8,082,240 | 0.74% | ? | universal | - | ^1.88.0 | 0 | - | in |
| 30 | [ms-toolsai.vscode-jupyter-slideshow](https://open-vsx.org/extension/ms-toolsai/vscode-jupyter-slideshow) | 0.1.6 | 8,035,138 | 0.74% | ? | universal | - | ^1.88.0 | 0 | - | in |
| 31 | [devsense.intelli-php-vscode](https://open-vsx.org/extension/devsense/intelli-php-vscode) | 0.12.17700 | 7,852,858 | 0.72% | LicenseRef-LICENSE | linux-x64 | 3 (N) | ^1.70.0 | 0 | - | platform-unavailable |
| 32 | [redhat.vscode-yaml](https://open-vsx.org/extension/redhat/vscode-yaml) | 1.25.2026092308 | 7,814,452 | 0.72% | MIT | universal | - | ^1.63.0 | 0 | - | in |
| 33 | [rust-lang.rust-analyzer](https://open-vsx.org/extension/rust-lang/rust-analyzer) | 0.4.3061 | 7,029,060 | 0.64% | MIT OR Apache-2.0 | linux-arm64 | 1 (Y) | ^1.93.0 | 0 | - | in |
| 34 | [ms-azuretools.vscode-docker](https://open-vsx.org/extension/ms-azuretools/vscode-docker) | 2.0.0 | 6,814,911 | 0.62% | SEE LICENSE IN LICENSE.md | universal | - | ^1.92.0 | 0 | - | in |
| 35 | [saoudrizwan.claude-dev](https://open-vsx.org/extension/saoudrizwan/claude-dev) | 4.1.21 | 6,720,377 | 0.62% | Apache-2.0 | universal | - | ^1.101.0 | 0 | CHAT | in |
| 36 | [GitHub.vscode-pull-request-github](https://open-vsx.org/extension/GitHub/vscode-pull-request-github) | 0.166.1 | 6,554,527 | 0.60% | MIT | universal | - | ^1.137.0 | 32 | CHAT | in |
| 37 | [PKief.material-icon-theme](https://open-vsx.org/extension/PKief/material-icon-theme) | 5.38.1 | 6,080,100 | 0.56% | MIT | universal | - | ^1.55.0 | 0 | - | in |
| 38 | [dbaeumer.vscode-eslint](https://open-vsx.org/extension/dbaeumer/vscode-eslint) | 3.0.34 | 5,997,165 | 0.55% | MIT | universal | - | ^1.90.0 | 0 | - | in |
| 39 | [Dart-Code.dart-code](https://open-vsx.org/extension/Dart-Code/dart-code) | 3.143.20260901 | 5,616,619 | 0.51% | SEE LICENSE IN LICENSE | universal | - | ^1.101.0 | 0 | DAP/CHAT | in |
| 40 | [jlcodes.antigravity-cockpit](https://open-vsx.org/extension/jlcodes/antigravity-cockpit) | 2.1.52 | 4,995,841 | 0.46% | ? | universal | - | ^1.90.0 | 0 | - | in |
| 41 | [Dart-Code.flutter](https://open-vsx.org/extension/Dart-Code/flutter) | 3.143.20260901 | 4,829,530 | 0.44% | SEE LICENSE IN LICENSE | universal | - | ^1.101.0 | 0 | - | in |
| 42 | [GitHub.vscode-github-actions](https://open-vsx.org/extension/GitHub/vscode-github-actions) | 0.32.3 | 4,411,985 | 0.40% | MIT | universal | - | ^1.72.0 | 0 | DAP | in |
| 43 | [quarto.quarto](https://open-vsx.org/extension/quarto/quarto) | 1.138.0 | 4,352,736 | 0.40% | MIT | universal | - | ^1.75.0 | 0 | NB | in |
| 44 | [kilocode.kilo-code](https://open-vsx.org/extension/kilocode/kilo-code) | 7.8.0 | 4,206,777 | 0.39% | MIT | linux-arm64 | 4 (Y) | ^1.105.1 | 0 | - | in |
| 45 | [Google.geminicodeassist](https://open-vsx.org/extension/Google/geminicodeassist) | 2.100.0 | 4,007,784 | 0.37% | SEE LICENSE IN LICENSE | universal | - | ^1.97.0 | 0 | - | in |
| 46 | [anyscalecompute.anyscale-workspaces](https://open-vsx.org/extension/anyscalecompute/anyscale-workspaces) | 0.2.45 | 3,952,269 | 0.36% | ? | universal | - | ^1.66.0 | 0 | - | in |
| 48 | [amazonwebservices.aws-toolkit-vscode](https://open-vsx.org/extension/amazonwebservices/aws-toolkit-vscode) | 4.15.0 | 3,525,260 | 0.32% | Apache-2.0 | universal | - | ^1.83.0 | 0 | DAP/NB | in |
| 49 | [charliermarsh.ruff](https://open-vsx.org/extension/charliermarsh/ruff) | 2026.84.0 | 3,477,732 | 0.32% | MIT | linux-arm64 | 1 (Y) | ^1.75.0 | 0 | - | in |
| 50 | [mechatroner.rainbow-csv](https://open-vsx.org/extension/mechatroner/rainbow-csv) | 3.24.1 | 3,195,691 | 0.29% | MIT | universal | - | ^1.95.0 | 0 | - | in |
| 51 | [ms-vscode.js-debug](https://open-vsx.org/extension/ms-vscode/js-debug) | 1.140.0 | 3,130,719 | 0.29% | MIT | universal | 2 (N) | ^1.80.0 | 4 | DAP | in |
| 52 | [googlecloudtools.datacloud](https://open-vsx.org/extension/googlecloudtools/datacloud) | 0.11.0 | 3,035,780 | 0.28% | SEE LICENSE IN LICENSE | universal | - | ^1.97.0 | 0 | NB/CHAT | in |
| 53 | [ritwickdey.LiveServer](https://open-vsx.org/extension/ritwickdey/LiveServer) | 5.7.10 | 2,963,566 | 0.27% | MIT | universal | 1 (N) | ^1.51.0 | 0 | - | in |
| 54 | [posit.shiny](https://open-vsx.org/extension/posit/shiny) | 1.4.3 | 2,912,829 | 0.27% | ? | universal | - | ^1.96.0 | 0 | CHAT | in |
| 55 | [Angular.ng-template](https://open-vsx.org/extension/Angular/ng-template) | 22.1.1 | 2,819,912 | 0.26% | MIT | universal | - | ^1.74.3 | 0 | - | in |
| 56 | [Codeium.windsurfPyright](https://open-vsx.org/extension/Codeium/windsurfPyright) | 1.29.6 | 2,763,442 | 0.25% | SEE LICENSE IN LICENSE.txt | universal | - | ^1.99.0 | 0 | - | in |
| 57 | [rangav.vscode-thunder-client](https://open-vsx.org/extension/rangav/vscode-thunder-client) | 2.41.3 | 2,706,986 | 0.25% | ? | universal | - | ^1.85.0 | 0 | - | in |
| 58 | [GitLab.gitlab-workflow](https://open-vsx.org/extension/GitLab/gitlab-workflow) | 6.90.3 | 2,607,128 | 0.24% | MIT | universal | 1 (N) | ^1.92.2 | 0 | - | in |
| 59 | [bradlc.vscode-tailwindcss](https://open-vsx.org/extension/bradlc/vscode-tailwindcss) | 0.16.0 | 2,492,674 | 0.23% | MIT | universal | - | ^1.67.0 | 0 | - | in |
| 60 | [ms-vscode.powershell](https://open-vsx.org/extension/ms-vscode/powershell) | 2025.4.0 | 2,442,965 | 0.22% | SEE LICENSE IN LICENSE.txt | universal | 151 (N) | ^1.101.0 | 0 | DAP | in |
| 61 | [detachhead.basedpyright](https://open-vsx.org/extension/detachhead/basedpyright) | 1.40.1 | 2,432,868 | 0.22% | MIT | universal | - | ^1.101.0 | 0 | - | in |
| 62 | [anysphere.pyright](https://open-vsx.org/extension/anysphere/pyright) | 1.1.327 | 2,288,633 | 0.21% | MIT | universal | - | ^1.78.0 | 0 | - | in |
| 63 | [hashicorp.terraform](https://open-vsx.org/extension/hashicorp/terraform) | 2.40.0 | 2,287,737 | 0.21% | MPL-2.0 | linux-arm64 | 1 (Y) | ^1.92.2 | 0 | CHAT | in |
| 64 | [bmewburn.vscode-intelephense-client](https://open-vsx.org/extension/bmewburn/vscode-intelephense-client) | 1.18.5 | 2,197,917 | 0.20% | SEE LICENSE IN LICENSE.txt | universal | - | ^1.91.0 | 0 | - | in |
| 65 | [EditorConfig.EditorConfig](https://open-vsx.org/extension/EditorConfig/EditorConfig) | 0.18.2 | 2,189,849 | 0.20% | MIT | universal | - | ^1.100.0 | 0 | - | in |
| 66 | [shd101wyy.markdown-preview-enhanced](https://open-vsx.org/extension/shd101wyy/markdown-preview-enhanced) | 0.8.36 | 2,145,913 | 0.20% | NCSA | universal | - | ^1.82.0 | 0 | - | in |
| 67 | [ms-vscode.cmake-tools](https://open-vsx.org/extension/ms-vscode/cmake-tools) | 1.24.42 | 2,132,483 | 0.20% | MIT | universal | 1 (N) | ^1.88.0 | 0 | DAP | in |
| 68 | [amazonwebservices.amazon-q-vscode](https://open-vsx.org/extension/amazonwebservices/amazon-q-vscode) | 2.7.0 | 2,124,416 | 0.19% | Apache-2.0 | universal | 3 (N) | ^1.83.0 | 0 | - | in |
| 69 | [svelte.svelte-vscode](https://open-vsx.org/extension/svelte/svelte-vscode) | 110.3.1 | 2,105,343 | 0.19% | MIT | universal | - | ^1.82.0 | 0 | - | in |
| 70 | [ms-vscode.js-debug-companion](https://open-vsx.org/extension/ms-vscode/js-debug-companion) | 1.1.3 | 2,095,352 | 0.19% | MIT | universal | - | ^1.90.0 | 0 | - | in |
| 71 | [ms-vscode.live-server](https://open-vsx.org/extension/ms-vscode/live-server) | 0.4.20 | 2,086,595 | 0.19% | ? | universal | - | ^1.109.0 | 0 | - | in |
| 72 | [Prisma.prisma-insider](https://open-vsx.org/extension/Prisma/prisma-insider) | 31.12.11 | 2,074,001 | 0.19% | Apache-2.0 | universal | - | ^1.104.0 | 0 | CHAT | in |
| 73 | [cweijan.vscode-office](https://open-vsx.org/extension/cweijan/vscode-office) | 4.2.0 | 2,069,323 | 0.19% | ? | universal | - | ^1.64.0 | 0 | CHAT | in |
| 74 | [synedra.auto-run-command](https://open-vsx.org/extension/synedra/auto-run-command) | 1.6.1 | 2,058,572 | 0.19% | MIT | universal | - | ^1.60.0 | 0 | - | in |
| 75 | [jmxgrog.vscode-nuget-package-manager](https://open-vsx.org/extension/jmxgrog/vscode-nuget-package-manager) | 1.1.6 | 2,043,987 | 0.19% | MIT | universal | - | ^1.10.0 | 0 | - | in |
| 76 | [ms-vscode.vscode-js-profile-table](https://open-vsx.org/extension/ms-vscode/vscode-js-profile-table) | 1.0.11 | 2,038,083 | 0.19% | MIT | universal | - | ^1.74.0 | 0 | - | in |
| 77 | [ms-dotnettools.vscode-dotnet-runtime](https://open-vsx.org/extension/ms-dotnettools/vscode-dotnet-runtime) | 3.2.0 | 1,973,610 | 0.18% | MIT | universal | - | ^1.101.0 | 0 | CHAT | in |
| 78 | [RooVeterinaryInc.roo-cline](https://open-vsx.org/extension/RooVeterinaryInc/roo-cline) | 3.54.0 | 1,970,644 | 0.18% | ? | universal | - | ^1.84.0 | 0 | CHAT | in |
| 79 | [REditorSupport.r](https://open-vsx.org/extension/REditorSupport/r) | 2.8.8 | 1,946,555 | 0.18% | SEE LICENSE IN LICENSE | universal | - | ^1.75.0 | 0 | - | in |
| 80 | [streetsidesoftware.code-spell-checker](https://open-vsx.org/extension/streetsidesoftware/code-spell-checker) | 4.9.3 | 1,937,197 | 0.18% | GPL-3.0-or-later | universal | - | ^1.104.0 | 0 | - | in |
| 81 | [vscodevim.vim](https://open-vsx.org/extension/vscodevim/vim) | 1.32.4 | 1,906,591 | 0.17% | MIT | universal | - | ^1.74.0 | 0 | - | in |
| 82 | [SonarSource.sonarlint-vscode](https://open-vsx.org/extension/SonarSource/sonarlint-vscode) | 5.10.0 | 1,869,155 | 0.17% | SEE LICENSE IN LICENSE.txt | universal | - | ^1.99.3 | 0 | CHAT | in |
| 83 | [MermaidChart.vscode-mermaid-chart](https://open-vsx.org/extension/MermaidChart/vscode-mermaid-chart) | 2.8.1 | 1,846,433 | 0.17% | ? | universal | - | ^1.77.0 | 1 | CHAT | in |
| 84 | [VMware.vscode-spring-boot](https://open-vsx.org/extension/VMware/vscode-spring-boot) | 2.5.2026092400 | 1,813,829 | 0.17% | EPL-1.0 | universal | - | ^1.92.0 | 0 | CHAT | in |
| 85 | [DavidAnson.vscode-markdownlint](https://open-vsx.org/extension/DavidAnson/vscode-markdownlint) | 0.62.1 | 1,767,395 | 0.16% | MIT | universal | - | ^1.97.0 | 0 | - | in |
| 86 | [posit.publisher](https://open-vsx.org/extension/posit/publisher) | 2.13.11 | 1,733,334 | 0.16% | MIT | universal | - | ^1.105.0 | 0 | CHAT | in |
| 87 | [Continue.continue](https://open-vsx.org/extension/Continue/continue) | 2.1.0 | 1,642,136 | 0.15% | Apache-2.0 | linux-arm64 | 17 (Y) | ^1.70.0 | 0 | - | in |
| 88 | [tomoki1207.pdf](https://open-vsx.org/extension/tomoki1207/pdf) | 1.2.2 | 1,627,109 | 0.15% | ? | universal | - | ^1.46.0 | 0 | - | in |
| 89 | [Google.gemini-cli-vscode-ide-companion](https://open-vsx.org/extension/Google/gemini-cli-vscode-ide-companion) | 0.20.0 | 1,574,966 | 0.14% | LICENSE | universal | - | ^1.99.0 | 0 | - | in |
| 90 | [redhat.vscode-xml](https://open-vsx.org/extension/redhat/vscode-xml) | 0.29.2026091508 | 1,570,337 | 0.14% | EPL-2.0 | linux-arm64 | 1 (Y) | ^1.67.0 | 0 | - | in |
| 91 | [vscode-icons-team.vscode-icons](https://open-vsx.org/extension/vscode-icons-team/vscode-icons) | 12.19.0 | 1,464,158 | 0.13% | MIT | universal | - | ^1.82.0 | 0 | - | in |
| 93 | [Prisma.prisma](https://open-vsx.org/extension/Prisma/prisma) | 31.12.10 | 1,443,306 | 0.13% | Apache-2.0 | universal | - | ^1.104.0 | 0 | CHAT | in |
| 94 | [highagency.pencildev](https://open-vsx.org/extension/highagency/pencildev) | 0.6.73 | 1,440,512 | 0.13% | SEE LICENSE IN LICENSE | universal | 7 (Y) | ^1.100.0 | 0 | - | in |
| 95 | [cweijan.vscode-mysql-client2](https://open-vsx.org/extension/cweijan/vscode-mysql-client2) | 9.0.2 | 1,347,164 | 0.12% | ? | universal | - | ^1.68.0 | 0 | NB/CHAT | in |
| 96 | [ms-python.black-formatter](https://open-vsx.org/extension/ms-python/black-formatter) | 2025.2.0 | 1,322,934 | 0.12% | MIT | universal | - | ^1.82.0 | 0 | - | in |
| 97 | [vadimcn.vscode-lldb](https://open-vsx.org/extension/vadimcn/vscode-lldb) | 1.12.3 | 1,304,154 | 0.12% | MIT | universal | - | ^1.61.0 | 0 | DAP/CHAT | in |
| 98 | [ms-kubernetes-tools.vscode-kubernetes-tools](https://open-vsx.org/extension/ms-kubernetes-tools/vscode-kubernetes-tools) | 1.4.1 | 1,206,462 | 0.11% | Apache-2.0 | universal | - | ^1.110.0 | 0 | - | in |
| 99 | [muhammad-sammy.csharp](https://open-vsx.org/extension/muhammad-sammy/csharp) | 2.145.21-g154a82fd27 | 1,128,412 | 0.10% | SEE LICENSE IN RuntimeLicenses/li… | linux-arm64 | 646 (Y) | ^1.106.0 | 0 | DAP | in |
| 100 | [mhutchie.git-graph](https://open-vsx.org/extension/mhutchie/git-graph) | 1.30.0 | 1,120,180 | 0.10% | ? | universal | - | ^1.38.0 | 0 | - | in |
| 101 | [mtxr.sqltools](https://open-vsx.org/extension/mtxr/sqltools) | 0.28.6 | 1,091,678 | 0.10% | MIT | universal | - | ^1.78.0 | 0 | - | in |
| 102 | [usernamehw.errorlens](https://open-vsx.org/extension/usernamehw/errorlens) | 3.28.0 | 1,060,030 | 0.10% | MIT | universal | - | ^1.107.0 | 0 | - | in |
| 103 | [astro-build.astro-vscode](https://open-vsx.org/extension/astro-build/astro-vscode) | 2.16.20 | 1,053,439 | 0.10% | MIT | linux-arm64 | - | ^1.101.0 | 0 | - | in |
| 104 | [huijbzhou.githd](https://open-vsx.org/extension/huijbzhou/githd) | 2.5.7 | 1,044,298 | 0.10% | MIT | universal | - | ^1.91.0 | 0 | - | in |
| 105 | [ms-pyright.pyright](https://open-vsx.org/extension/ms-pyright/pyright) | 1.1.402 | 1,036,974 | 0.10% | MIT | universal | - | ^1.99.0 | 0 | - | in |
| 106 | [NexrallCode.nexrall-code-vscode](https://open-vsx.org/extension/NexrallCode/nexrall-code-vscode) | 1.0.132 | 1,012,368 | 0.09% | ? | universal | - | ^1.90.0 | 0 | - | in |
| 107 | [formulahendry.code-runner](https://open-vsx.org/extension/formulahendry/code-runner) | 0.12.2 | 993,974 | 0.09% | ? | universal | - | ^1.56.0 | 0 | - | in |
| 108 | [vscode.npm](https://open-vsx.org/extension/vscode/npm) | 1.95.3 | 987,665 | 0.09% | SEE LICENSE IN LICENSE-vscode.txt | universal | - | 0.10.0 | 1 | - | in |
| 109 | [ajsqnhort.include-autocomplete](https://open-vsx.org/extension/ajsqnhort/include-autocomplete) | 0.0.4 | 984,004 | 0.09% | MIT | universal | - | ^1.5.0 | 0 | - | in |
| 110 | [eclipse-cdt.cdt-gdb-vscode](https://open-vsx.org/extension/eclipse-cdt/cdt-gdb-vscode) | 2.9.1 | 960,247 | 0.09% | EPL-2.0 | universal | 17 (Y) | ^1.78.0 | 0 | DAP | in |
| 113 | [kade.kade](https://open-vsx.org/extension/kade/kade) | 4.0.4 | 921,173 | 0.08% | ? | universal | - | ^1.84.0 | 0 | CHAT | in |
| 114 | [qwtel.sqlite-viewer](https://open-vsx.org/extension/qwtel/sqlite-viewer) | 26.9.1 | 919,758 | 0.08% | LICENSE.md | linux-arm64 | 1 (Y) | ^1.83.1 | 0 | - | in |
| 115 | [Alibaba-Cloud.tongyi-lingma](https://open-vsx.org/extension/Alibaba-Cloud/tongyi-lingma) | 2.6.10 | 861,824 | 0.08% | ? | universal | - | ^1.68.0 | 0 | CHAT | in |
| 116 | [ms-python.isort](https://open-vsx.org/extension/ms-python/isort) | 2025.0.0 | 859,870 | 0.08% | MIT | universal | - | ^1.74.0 | 0 | - | in |
| 117 | [dsznajder.es7-react-js-snippets](https://open-vsx.org/extension/dsznajder/es7-react-js-snippets) | 4.4.3 | 859,469 | 0.08% | MIT | universal | - | ^1.60.0 | 0 | - | in |
| 118 | [zhuangtongfa.material-theme](https://open-vsx.org/extension/zhuangtongfa/material-theme) | 3.20.2 | 854,751 | 0.08% | MIT | universal | - | ^1.76.0 | 0 | - | in |
| 119 | [firsttris.vscode-jest-runner](https://open-vsx.org/extension/firsttris/vscode-jest-runner) | 0.4.148 | 838,525 | 0.08% | MIT | universal | - | ^1.100.0 | 0 | - | in |
| 120 | [James-Yu.latex-workshop](https://open-vsx.org/extension/James-Yu/latex-workshop) | 10.19.0 | 833,275 | 0.08% | MIT | universal | - | ^1.114.0 | 0 | - | in |
| 122 | [jeanp413.open-remote-ssh](https://open-vsx.org/extension/jeanp413/open-remote-ssh) | 0.3.1 | 829,326 | 0.08% | ? | universal | - | ^1.70.2 | 2 | - | in |
| 123 | [googlecloudtools.firebase-dataconnect-vscode](https://open-vsx.org/extension/googlecloudtools/firebase-dataconnect-vscode) | 2.4.3 | 821,392 | 0.08% | ? | universal | 1 (N) | ^1.69.0 | 0 | - | in |
| 124 | [ms-vscode.node-debug2](https://open-vsx.org/extension/ms-vscode/node-debug2) | 1.43.0 | 810,943 | 0.07% | MIT | universal | - | ^1.60.0-insider | 0 | DAP | oos:DAP |
| 125 | [dbcode.dbcode](https://open-vsx.org/extension/dbcode/dbcode) | 1.38.7 | 802,679 | 0.07% | SEE LICENSE IN LICENSE | universal | 8 (Y) | ^1.101.0 | 0 | DAP/NB/CHAT | in |
| 126 | [vitest.explorer](https://open-vsx.org/extension/vitest/explorer) | 1.52.0 | 799,631 | 0.07% | MIT | universal | - | ^1.88.0 | 0 | - | in |
| 127 | [qwenlm.qwen-code-vscode-ide-companion](https://open-vsx.org/extension/qwenlm/qwen-code-vscode-ide-companion) | 0.24.5 | 789,501 | 0.07% | LICENSE | universal | 6 (Y) | ^1.96.0 | 0 | - | in |
| 128 | [bierner.markdown-mermaid](https://open-vsx.org/extension/bierner/markdown-mermaid) | 1.32.1 | 786,516 | 0.07% | MIT | universal | - | ^1.100.0 | 0 | NB | in |
| 129 | [atlassian.atlascode](https://open-vsx.org/extension/atlassian/atlascode) | 4.1.200 | 771,563 | 0.07% | SEE LICENSE IN LICENSE | universal | - | ^1.77.0 | 0 | - | in |
| 130 | [ecmel.vscode-html-css](https://open-vsx.org/extension/ecmel/vscode-html-css) | 2.0.14 | 767,502 | 0.07% | MIT | universal | - | ^1.86.0 | 0 | - | in |
| 131 | [xdebug.php-debug](https://open-vsx.org/extension/xdebug/php-debug) | 1.40.2 | 752,681 | 0.07% | MIT | universal | - | ^1.66.1 | 0 | DAP | oos:DAP |
| 132 | [cweijan.dbclient-jdbc](https://open-vsx.org/extension/cweijan/dbclient-jdbc) | 1.4.2 | 718,630 | 0.07% | ? | universal | - | ^1.60.0 | 0 | - | in |
| 133 | [Oracle.oracle-java](https://open-vsx.org/extension/Oracle/oracle-java) | 26.0.2 | 717,250 | 0.07% | Apache 2.0 | universal | 32 (Y) | ^1.84.0 | 0 | DAP/NB | in |
| 134 | [stylelint.vscode-stylelint](https://open-vsx.org/extension/stylelint/vscode-stylelint) | 2.2.1 | 705,370 | 0.06% | MIT | universal | - | >=1.103.0 | 0 | - | in |
| 135 | [henrikdev.ag-quota](https://open-vsx.org/extension/henrikdev/ag-quota) | 1.1.1 | 688,151 | 0.06% | MIT | universal | - | ^1.90.0 | 0 | - | in |
| 136 | [Google.colab](https://open-vsx.org/extension/Google/colab) | 0.9.6 | 686,746 | 0.06% | ? | universal | - | ^1.99.3 | 0 | - | in |
| 137 | [timonwong.shellcheck](https://open-vsx.org/extension/timonwong/shellcheck) | 0.40.1 | 677,357 | 0.06% | MIT | linux-arm64 | 1 (Y) | ^1.100.0 | 0 | - | in |
| 138 | [redhat.ansible](https://open-vsx.org/extension/redhat/ansible) | 26.8.2 | 674,807 | 0.06% | MIT | universal | - | ^1.91.0 | 0 | CHAT | in |
| 139 | [fwcd.kotlin](https://open-vsx.org/extension/fwcd/kotlin) | 0.2.36 | 674,378 | 0.06% | MIT | universal | - | ^1.52.0 | 0 | DAP | in |
| 140 | [Toaock.vscode-css-custom-properties](https://open-vsx.org/extension/Toaock/vscode-css-custom-properties) | 0.0.5 | 670,732 | 0.06% | MIT | universal | - | ^1.56.0 | 0 | - | in |
| 141 | [yzhang.markdown-all-in-one](https://open-vsx.org/extension/yzhang/markdown-all-in-one) | 3.6.2 | 665,288 | 0.06% | MIT | universal | - | ^1.77.0 | 0 | - | in |
| 142 | [nrwl.angular-console](https://open-vsx.org/extension/nrwl/angular-console) | 18.101.1 | 665,081 | 0.06% | MIT | universal | - | ^1.99.3 | 0 | CHAT | in |
| 143 | [coderabbit.coderabbit-vscode](https://open-vsx.org/extension/coderabbit/coderabbit-vscode) | 0.21.8 | 656,044 | 0.06% | See LICENSE | universal | - | ^1.93.1 | 0 | - | in |
| 144 | [tamasfe.even-better-toml](https://open-vsx.org/extension/tamasfe/even-better-toml) | 0.21.2 | 649,892 | 0.06% | SEE LICENSE IN LICENSE.md | universal | - | ^1.90.0 | 0 | - | in |
| 145 | [donjayamanne.githistory](https://open-vsx.org/extension/donjayamanne/githistory) | 0.6.20 | 643,456 | 0.06% | MIT | universal | - | ^1.76.0 | 0 | - | in |
| 147 | [k--kato.intellij-idea-keybindings](https://open-vsx.org/extension/k--kato/intellij-idea-keybindings) | 1.7.8 | 635,105 | 0.06% | MIT | universal | - | ^1.138.0 | 0 | - | in |
| 148 | [salesforce.salesforcedx-vscode-core](https://open-vsx.org/extension/salesforce/salesforcedx-vscode-core) | 67.23.1 | 619,905 | 0.06% | BSD-3-Clause | universal | - | ^1.90.0 | 0 | - | in |
| 149 | [TabNine.tabnine-vscode](https://open-vsx.org/extension/TabNine/tabnine-vscode) | 3.346.8 | 610,468 | 0.06% | License at https://tabnine.com/eu… | universal | 6 (N) | ^1.50.0 | 0 | - | in |
| 150 | [malloydata.malloy-vscode](https://open-vsx.org/extension/malloydata/malloy-vscode) | 0.3.1789753698 | 607,931 | 0.06% | MIT | linux-arm64 | 2 (Y) | ^1.82.0 | 0 | NB | in |
| 151 | [yossisa.cursor-usage](https://open-vsx.org/extension/yossisa/cursor-usage) | 1.3.0 | 603,466 | 0.06% | MIT | universal | - | ^1.74.0 | 0 | - | in |
| 153 | [aaron-bond.better-comments](https://open-vsx.org/extension/aaron-bond/better-comments) | 3.0.2 | 592,632 | 0.05% | MIT | universal | - | ^1.65.0 | 0 | - | in |
| 154 | [vscode.json-language-features](https://open-vsx.org/extension/vscode/json-language-features) | 1.95.3 | 592,510 | 0.05% | SEE LICENSE IN LICENSE-vscode.txt | universal | - | ^1.77.0 | 1 | - | in |
| 156 | [waderyan.gitblame](https://open-vsx.org/extension/waderyan/gitblame) | 13.1.0 | 590,612 | 0.05% | MIT | universal | - | >=1.100.0 | 0 | - | in |
| 157 | [formulahendry.auto-rename-tag](https://open-vsx.org/extension/formulahendry/auto-rename-tag) | 0.1.10 | 590,026 | 0.05% | MIT | universal | - | ^1.41.1 | 0 | - | in |
| 158 | [kombai.kombai](https://open-vsx.org/extension/kombai/kombai) | 2.0.128 | 587,470 | 0.05% | SEE LICENSE IN LICENSE | universal | 11 (N) | ^1.84.1 | 0 | - | in |
| 160 | [salesforce.salesforcedx-vscode-apex](https://open-vsx.org/extension/salesforce/salesforcedx-vscode-apex) | 67.23.1 | 568,249 | 0.05% | BSD-3-Clause | universal | - | ^1.90.0 | 0 | - | in |
| 161 | [pokey.cursorless](https://open-vsx.org/extension/pokey/cursorless) | 1.1.1865 | 566,390 | 0.05% | MIT | universal | - | ^1.98.0 | 0 | - | in |
| 162 | [ms-vscode.makefile-tools](https://open-vsx.org/extension/ms-vscode/makefile-tools) | 0.12.17 | 564,693 | 0.05% | SEE LICENSE IN LICENSE.txt | universal | - | ^1.74.0 | 0 | - | in |
| 163 | [yandeu.five-server](https://open-vsx.org/extension/yandeu/five-server) | 0.4.0 | 563,198 | 0.05% | SEE LICENSE IN LICENSE | universal | - | ^1.90.0 | 0 | - | in |
| dep | [vscode.yaml](https://open-vsx.org/extension/vscode/yaml) | 1.95.3 | 440,538 | 0.04% | SEE LICENSE IN LICENSE-vscode.txt | universal | - | * | 0 | - | in |
| dep | [vscode.docker](https://open-vsx.org/extension/vscode/docker) | 1.95.3 | 377,420 | 0.03% | SEE LICENSE IN LICENSE-vscode.txt | universal | - | * | 0 | - | in |
| dep | [vscode.git](https://open-vsx.org/extension/vscode/git) | 1.95.3 | 315,692 | 0.03% | SEE LICENSE IN LICENSE-vscode.txt | universal | - | ^1.5.0 | 25 | - | in |
| dep | [vscode.git-base](https://open-vsx.org/extension/vscode/git-base) | 1.95.3 | 207,588 | 0.02% | SEE LICENSE IN LICENSE-vscode.txt | universal | - | 0.10.0 | 0 | - | in |
| dep | [vscode.github-authentication](https://open-vsx.org/extension/vscode/github-authentication) | 1.95.3 | 133,115 | 0.01% | SEE LICENSE IN LICENSE-vscode.txt | universal | - | ^1.41.0 | 0 | - | in |
<!-- gen:end full_table -->

### In-scope classification rule (D7) and every exclusion

Applied in order, mechanically, by `render-corpus.mjs` `classify()` against `data/corpus.json`:

1. **`platform-unavailable`**: `noGlibcArm64 === true` — no `linux-arm64` and no `universal` build is published, so the
   extension cannot install on the device target at all (the corpus keeps it only for size/weight bookkeeping).
2. **`oos:DAP`**: `contributes.debuggers > 0` **and** the extension contributes no `languages` and no `views` beyond the
   baseline `webviewViews: 0` (i.e. it has no language or view contribution unrelated to being a debug adapter) — the
   two named examples from the brief, `ms-python.debugpy` and `vscjava.vscode-java-debug`, match this exactly. Mixed
   extensions that both contribute a language/real view **and** a debugger (e.g. `golang.Go`, `Dart-Code.dart-code`,
   `ms-vscode.js-debug`, `vadimcn.vscode-lldb`, `ms-toolsai.jupyter`) stay `in`; only their `contributes.debuggers`
   surface is out of scope for OOS-DAP scoring purposes (flagged `DAP` in the `OOS` column of `## 2`).
3. **`oos:NB`**: every contribution point the extension declares is one of `notebookRenderer` / `notebookPreload` /
   `notebooks` (a pure notebook-output-renderer package with no commands, languages or views of its own).
4. **`oos:CHAT`**: every contribution point is one of `chatParticipants` / `languageModelTools` / `chatSkills` /
   `chatInstructions` / `chatContext` / `mcpServerDefinitionProviders` / `languageModelToolSets` (a pure chat-participant
   package). No extension in this corpus matches this rule as its *sole* purpose (0 found, 0 weight) — chat/lm
   functionality always rides along with a language or general-purpose extension here, so it stays `in` and is flagged
   `CHAT` instead (its chat surface is scored under OOS-CHAT, its non-chat surface under `S_in`).
5. Otherwise: **`in`**.

Note: `ms-toolsai.vscode-jupyter-cell-tags` and `ms-toolsai.vscode-jupyter-slideshow` are Jupyter-notebook-only in
*practice* (`onNotebook:jupyter-notebook` activation, no purpose outside notebooks) but contribute ordinary
`commands`/`menus`/`views` points, not bare `notebookRenderer`/`notebookPreload`, so rule 3 does not exclude them; they
stay `in` E_in and will simply gate-fail (`g1`/`g2`) until notebook support exists, which is an honest accounting, not a
silent exclusion — flagged `NB` in `## 2`. `ms-toolsai.jupyter-renderers` (bare `notebookRenderer`+`notebookPreload`,
nothing else) does match rule 3 and is excluded.

<!-- gen:start exclusions -->
| class | id | downloads | weight% | reason |
|---|---|---|---|---|
| platform-unavailable | devsense.intelli-php-vscode | 7,852,858 | 0.72% | no linux-arm64/universal build published; falls back to linux-x64 |
| oos:DAP | ms-python.debugpy | 60,064,855 | 5.50% | debug-adapter-only: contributes.debuggers=1, no languages/views contributions |
| oos:DAP | vscjava.vscode-java-debug | 26,023,408 | 2.38% | debug-adapter-only: contributes.debuggers=1, no languages/views contributions |
| oos:DAP | ms-vscode.node-debug2 | 810,943 | 0.07% | debug-adapter-only: contributes.debuggers=2, no languages/views contributions |
| oos:DAP | xdebug.php-debug | 752,681 | 0.07% | debug-adapter-only: contributes.debuggers=1, no languages/views contributions |
| oos:NB | ms-toolsai.jupyter-renderers | 9,755,102 | 0.89% | notebook-renderer-only contribution points (notebookPreload, notebookRenderer) |

E_in = 150 extensions, 986,096,967 downloads (90.36% of corpus weight). Excluded: 6 extensions, 105,259,847 downloads (9.64%).
<!-- gen:end exclusions -->

## 3. Aggregates (generated, weighted by `downloadCount`)

### Target platform distribution

<!-- gen:start target_dist -->
| target | #ext | weight% |
|---|---|---|
| universal | 138 | 70.12% |
| linux-arm64 | 17 | 29.17% |
| linux-x64 | 1 | 0.72% |
<!-- gen:end target_dist -->

### `engines.vscode` distribution and maximum

<!-- gen:start engines_dist -->
| engines.vscode lower bound | #ext |
|---|---|
| <=1.60 | 21 |
| 1.61-1.79 | 41 |
| 1.80-1.89 | 21 |
| 1.90-1.99 | 40 |
| 1.100-1.105 | 25 |
| 1.106-1.114 | 6 |
| 1.115-1.136 | 0 |
| 1.137+ | 2 |

Highest engines.vscode requirement: **k--kato.intellij-idea-keybindings** (`^1.138.0`). Top 5: k--kato.intellij-idea-keybindings `^1.138.0`, GitHub.vscode-pull-request-github `^1.137.0`, James-Yu.latex-workshop `^1.114.0`, ms-python.vscode-python-envs `^1.110.0-20260204`, ms-kubernetes-tools.vscode-kubernetes-tools `^1.110.0`.
<!-- gen:end engines_dist -->

**Consequence**: the emulated `vscode.version` (D9) must be reported as `>= 1.138` for every must-work extension's
`engines.vscode` gate to pass; the pinned vendor tag is `1.139` or `1.140` (`$VS/package.json` main is `1.140.0` at
snapshot time; D1 route pins a **stable** tag, so `1.139.x` is the likely release pin, `1.140.x` if main has branched to
a release by then — resolve at implementation time, not here).

### Native binary summary

<!-- gen:start native_summary -->
Ships native binaries: **32** extensions, 32.76% weight. Of those, **21** (24.94%) have at least one aarch64 binary that is glibc-dynamic or statically linked (runnable as-is in the proot Ubuntu arm64/glibc sandbox); **11** (7.82%) do not.

| id | downloads | weight% | native binaries |
|---|---|---|---|
| ms-python.debugpy | 60,064,855 | 5.50% | elf/x86-64/glibc×2 |
| devsense.intelli-php-vscode | 7,852,858 | 0.72% | elf/x86-64/glibc×3 |
| ms-vscode.js-debug | 3,130,719 | 0.29% | pe/noarch×2 |
| ritwickdey.LiveServer | 2,963,566 | 0.27% | macho/noarch |
| GitLab.gitlab-workflow | 2,607,128 | 0.24% | elf/x86-64/static/none |
| ms-vscode.powershell | 2,442,965 | 0.22% | pe/noarch×151 |
| ms-vscode.cmake-tools | 2,132,483 | 0.20% | pe/noarch |
| amazonwebservices.amazon-q-vscode | 2,124,416 | 0.19% | pe/noarch×3 |
| googlecloudtools.firebase-dataconnect-vscode | 821,392 | 0.08% | macho/noarch |
| TabNine.tabnine-vscode | 610,468 | 0.06% | elf/aarch64/musl, elf/x86-64/musl, macho/noarch×2, pe/noarch×2 |
| kombai.kombai | 587,470 | 0.05% | macho/noarch×4, elf/x86-64/glibc×2, pe/noarch×4, elf/x86-64/musl |
<!-- gen:end native_summary -->

### Proposed API (`enabledApiProposals`) usage, top by weight

<!-- gen:start proposals_top -->
| key | #ext | call sites | weight% |
|---|---|---|---|
| terminalDataWriteEvent | 2 | 2 | 9.26% |
| editorHoverVerbosityLevel | 1 | 1 | 7.93% |
| portsAttributes | 3 | 3 | 6.74% |
| quickPickSortByLabel | 3 | 3 | 6.39% |
| notebookReplDocument | 2 | 2 | 6.36% |
| notebookVariableProvider | 2 | 2 | 6.36% |
| quickPickItemTooltip | 2 | 2 | 6.36% |
| contribEditorContentMenu | 3 | 3 | 6.05% |
| contribViewsWelcome | 2 | 2 | 5.53% |
| debugVisualization | 1 | 1 | 5.50% |
| codeActionAI | 1 | 1 | 5.42% |
| terminalExecuteCommandEvent | 1 | 1 | 5.42% |
<!-- gen:end proposals_top -->

Per D8 the allow-list is the intersection of VS Code's stable `product.json` `extensionEnabledApiProposals` (65 entries)
and what the adapter implements; the must-work-relevant subset to prioritise is `meta.pyrefly`'s
`editorHoverVerbosityLevel` and `ms-python.python`'s 9 proposals (see `corpus-profiles.md`) — no other must-work
extension declares proposals.

### Node builtin module usage

<!-- gen:start child_process -->
`child_process` is imported by **126** extensions (1,361 import sites), 87.73% of corpus weight - the single largest node-builtin surface (LSP servers, CLIs, git spawn process.spawn/exec).
<!-- gen:end child_process -->

<!-- gen:start node_builtins -->
| key | #ext | call sites | weight% |
|---|---|---|---|
| path | 141 | 6,606 | 95.96% |
| fs | 140 | 4,625 | 95.89% |
| util | 134 | 3,067 | 95.66% |
| os | 131 | 2,097 | 94.38% |
| crypto | 120 | 2,419 | 92.86% |
| child_process | 126 | 1,361 | 87.73% |
| net | 112 | 964 | 86.51% |
| https | 103 | 771 | 76.88% |
| stream | 111 | 2,548 | 75.81% |
| url | 112 | 1,451 | 73.58% |
| assert | 94 | 1,325 | 73.10% |
| events | 108 | 1,643 | 70.37% |
<!-- gen:end node_builtins -->

### Activation events, top by weight

Explicit (`activationEvents`, event-kind prefix):

<!-- gen:start activation_events -->
| key | #ext | call sites | weight% |
|---|---|---|---|
| onLanguage | 56 | 110 | 54.48% |
| workspaceContains | 42 | 164 | 39.42% |
| onCommand | 37 | 156 | 32.02% |
| onDebugResolve | 17 | 33 | 24.20% |
| onDebugInitialConfigurations | 9 | 9 | 19.27% |
| onStartupFinished | 58 | 59 | 18.00% |
| onWebviewPanel | 12 | 24 | 14.62% |
| onNotebook | 8 | 10 | 10.61% |
| onDebugDynamicConfigurations | 6 | 6 | 10.22% |
| onLanguageModelTool | 3 | 9 | 8.75% |
| onUri | 15 | 15 | 6.31% |
| onTerminalShellIntegration | 1 | 1 | 5.42% |
<!-- gen:end activation_events -->

Implicit (generated from `contributes`, VS Code `activationEventsGenerator`, event-kind prefix):

<!-- gen:start implicit_activation_events -->
| key | #ext | call sites | weight% |
|---|---|---|---|
| onCommand | 141 | 5,263 | 96.50% |
| onLanguage | 80 | 232 | 58.51% |
| onView | 69 | 261 | 44.20% |
| onTaskType | 18 | 24 | 25.46% |
| onWalkthrough | 22 | 25 | 19.45% |
| onLanguageModelTool | 16 | 87 | 16.13% |
| onCustomEditor | 18 | 32 | 14.58% |
| onMcpCollection | 8 | 10 | 6.40% |
<!-- gen:end implicit_activation_events -->

### `contributes` points, top 30 by weight

<!-- gen:start contributes_top -->
| key | #ext | call sites | weight% |
|---|---|---|---|
| commands | 144 | 5,267 | 97.07% |
| configuration | 142 | 4,755 | 95.63% |
| menus | 112 | 8,716 | 75.90% |
| languages | 80 | 234 | 58.51% |
| keybindings | 62 | 776 | 47.45% |
| views | 69 | 261 | 44.20% |
| debuggers | 26 | 41 | 37.84% |
| grammars | 55 | 203 | 33.82% |
| configurationDefaults | 35 | 75 | 33.05% |
| taskDefinitions | 19 | 24 | 29.21% |
| walkthroughs | 25 | 25 | 27.66% |
| viewsWelcome | 36 | 213 | 27.12% |
| jsonValidation | 31 | 54 | 26.43% |
| viewsContainers | 55 | 103 | 25.52% |
| breakpoints | 20 | 68 | 22.26% |
| submenus | 28 | 111 | 20.42% |
| semanticTokenScopes | 12 | 15 | 18.54% |
| languageModelTools | 16 | 87 | 16.13% |
| customEditors | 18 | 32 | 14.58% |
| javaExtensions | 7 | 39 | 13.70% |
| snippets | 16 | 35 | 9.96% |
| problemMatchers | 11 | 16 | 9.93% |
| chatSkills | 3 | 7 | 7.89% |
| yamlValidation | 6 | 21 | 6.51% |
| colors | 14 | 205 | 6.45% |
| mcpServerDefinitionProviders | 8 | 10 | 6.40% |
| javaShortcuts | 2 | 6 | 5.93% |
| semanticTokenTypes | 8 | 149 | 5.62% |
| icons | 21 | 512 | 5.54% |
| debugVisualizers | 1 | 1 | 5.50% |
<!-- gen:end contributes_top -->

### `menus` contribution points, top 20 by weight

<!-- gen:start menus_top -->
| key | #ext | call sites | weight% |
|---|---|---|---|
| commandPalette | 87 | 3,303 | 64.10% |
| view/title | 62 | 899 | 47.91% |
| editor/context | 68 | 194 | 47.63% |
| explorer/context | 47 | 135 | 38.51% |
| view/item/context | 49 | 2,205 | 37.96% |
| editor/title | 53 | 271 | 33.72% |
| editor/title/run | 13 | 25 | 23.42% |
| issue/reporter | 4 | 4 | 12.34% |
| file/newFile | 7 | 10 | 10.08% |
| editor/title/context | 13 | 36 | 9.35% |
| testing/item/context | 3 | 7 | 9.24% |
| webview/context | 9 | 375 | 8.81% |
| debug/variables/context | 6 | 19 | 7.75% |
| debug/callstack/context | 5 | 14 | 6.61% |
| javaProject.maven | 2 | 3 | 6.21% |
| javaProject.gradle | 2 | 3 | 6.13% |
| editor/content | 3 | 5 | 6.05% |
| comments/commentThread/title | 5 | 13 | 5.76% |
| testing/item/gutter | 2 | 5 | 5.49% |
| python.run | 1 | 4 | 5.42% |
<!-- gen:end menus_top -->

### `when`-clause context keys, top 30 by weight (of 1,189 distinct keys seen)

<!-- gen:start when_keys_top -->
| key | #ext | call sites | weight% |
|---|---|---|---|
| view | 64 | 64 | 52.65% |
| editorLangId | 47 | 47 | 40.01% |
| viewItem | 46 | 46 | 36.35% |
| resourceLangId | 32 | 32 | 33.82% |
| editorTextFocus | 40 | 40 | 32.77% |
| resourceFilename | 21 | 21 | 22.95% |
| workspaceFolderCount | 12 | 12 | 19.32% |
| explorerResourceIsFolder | 19 | 19 | 18.03% |
| editorFocus | 8 | 8 | 16.31% |
| resourceExtname | 17 | 17 | 15.98% |
| resourceScheme | 28 | 28 | 15.89% |
| virtualWorkspace | 7 | 7 | 15.59% |
| shellExecutionSupported | 6 | 6 | 15.33% |
| isInDiffEditor | 10 | 10 | 14.81% |
| javaLSReady | 5 | 5 | 12.68% |
| editorHasSelection | 23 | 23 | 11.64% |
| java:projectManagerActivated | 4 | 4 | 10.72% |
| webviewId | 5 | 5 | 10.58% |
| isWorkspaceTrusted | 7 | 7 | 10.57% |
| java:serverMode | 4 | 4 | 9.97% |
| activeWebviewPanelId | 14 | 14 | 9.54% |
| debugConfigurationType | 4 | 4 | 9.51% |
| inDiffEditor | 3 | 3 | 9.14% |
| inDebugMode | 11 | 11 | 7.85% |
| activeEditor | 7 | 7 | 7.72% |
| debugType | 9 | 9 | 7.45% |
| notebookType | 5 | 5 | 7.33% |
| findInputFocussed | 4 | 4 | 6.82% |
| replaceInputFocussed | 4 | 4 | 6.82% |
| workspacePlatform | 4 | 4 | 6.79% |
<!-- gen:end when_keys_top -->

## 4. Blockers ranked by usage weight

Corpus-wide (from [research/corpus-notes.md](research/corpus-notes.md), cross-checked against `## 3` above — the two
should read consistently since both derive from the same `corpus.json`/`corpus-usage.json`):

| blocker | #ext | weight share | notes |
|---|---|---|---|
| spawns child processes (`child_process` imported) | 126 | 87.7% | LSP servers, CLIs, git: `process.spawn`/`exec` is table stakes, not itself a gap |
| uses `window.createWebviewPanel` | 59 | 39.5% | plus 38 ext (14.8%) contributing webview *views*; webview security model is D5, already decided |
| ships native binaries (any format) | 32 | 32.8% | 21 ext (24.9%) have at least one usable aarch64 (glibc or static) binary; 6 (6.1%) are aarch64 `.node` addons needing N-API loading in proot |
| contributes `debuggers` | 22 | 29.5% | OOS-DAP track; flag is `contributes.debuggers > 0`, independent of the `oos:DAP` exclusion rule in `## 2` |
| chat/`lm` API use or chat/LM/MCP contributions | 31 | 28.6% | OOS-CHAT track |
| `enabledApiProposals` (any) | 14 | 26.9% | top by weight: `terminalDataWriteEvent` 9.3%, `editorHoverVerbosityLevel` 7.9% (`meta.pyrefly`, must-work), `portsAttributes` 6.7%, `quickPickSortByLabel` 6.4%, `notebookReplDocument`/`notebookVariableProvider`/`quickPickItemTooltip` 6.4% (`ms-python.python`, must-work), `contribEditorContentMenu` 6.0%, `contribViewsWelcome` 5.5%, `debugVisualization` 5.5% |
| `customEditors` | 18 | 14.6% | |
| ships ELF binaries with **none** for aarch64-glibc | 5 | 6.6% | `ms-python.debugpy` (must-work dependency: linux-arm64 package still ships x86-64 attach `.so`s), `devsense.intelli-php-vscode`, `GitLab.gitlab-workflow`, `eclipse-cdt.cdt-gdb-vscode`, `kombai.kombai` |
| wasm files | 26 | 4.7% | |
| notebooks / `notebookRenderer` | 10 | 3.2% | OOS-NB track |
| no glibc `linux-arm64` or `universal` build | 1 | 0.7% | `devsense.intelli-php-vscode` only — `platform-unavailable` |
| pack member not on Open VSX | 1 | - | `ms-python.vscode-pylance` (see `## 1`) |
| web-only (`browser`, no `main`) | 0 | 0% | none in this corpus |

**Must-work-specific blockers** (from [licensing-policy.md](licensing-policy.md) §3.2, cross-cited by id):

| id | blocker | source |
|---|---|---|
| `Anthropic.claude-code` | licence text forbids redistribution ("preinstalling or running Claude Code in your products ... requires agreeing to our Commercial Terms of Service"; NOTICE.md:87 "not redistributable") — **user-downloads only**, never bundled; 239 MB aarch64 native binary + 1 `.node` addon needs native loading in proot | licensing-policy.md §3.2, corpus-notes.md |
| `eamodio.gitlens` | `plus/` tree is under a separate non-OSI LICENSE.plus ("forbidden to copy, merge, publish, distribute, sublicense, and/or sell") — **user-downloads only**; never patch `plus/` code; also OOS-CHAT (uses `lm.*`/`chat.*`, contributes `mcpServerDefinitionProviders`) | licensing-policy.md §3.2 |
| `mhutchie.git-graph` | non-OSI licence text ("Permission is NOT GRANTED to publish, distribute, sublicense, and/or sell derivative works"), last publish 2021-04-17 (staleness risk for `engines.vscode` gate as VS Code API evolves) — **user-downloads only** | licensing-policy.md §3.2 |
| `redhat.java` | EPL-2.0 — redistribution needs EPL obligations reviewed by counsel; **user-downloads only**; 66 native binaries (37 MB) need native loading in proot; embedded JRE UNVERIFIED | licensing-policy.md §3.2 |
| `streetsidesoftware.code-spell-checker` | GPL-3.0-or-later (copyleft) — **user-downloads only**, no bundling | licensing-policy.md §3.2 |
| `ms-python.python` | pulls in `ms-python.debugpy` (`oos:DAP`) and `ms-python.vscode-python-envs` (both must-work-adjacent dependencies); OOS-CHAT (`lm.registerTool`, `languageModelTools:6`); 9 `enabledApiProposals` incl. `notebookReplDocument`/`notebookVariableProvider` (OOS-NB-adjacent) | corpus.json, corpus-notes.md |
| `GitHub.vscode-pull-request-github` | highest `enabledApiProposals` count in the must-work set (32), several unlikely to be on any near-term allow-list (`chatSessionsProvider`, `remoteCodingAgents`, `agentsWindowActivation`); `engines.vscode` `^1.137.0`, the joint-highest in the must-work set | corpus.json |
| `ms-azuretools.vscode-docker` | declarative stub, `extensionDependencies: [ms-azuretools.vscode-containers]` only — the real work (100 commands, 1 debugger = `oos:DAP`-flagged, `languageModelTools`) is in the dependency, not the must-work id itself; see `## 5` | corpus.json |

## 5. Must-work profiles

See [corpus-profiles.md](corpus-profiles.md): all 22 must-work ids, 5-10 lines each, generated from `corpus.json`.
Two notes not obvious from the generated profiles:

- **`ms-azuretools.vscode-docker` is a declarative stub** (`main: null`, 0 JS files, 0 activation events) whose entire
  function is `extensionDependencies: ["ms-azuretools.vscode-containers"]` (rank 20, 14,319,357 dl, `in` scope,
  `DAP`+`CHAT` flagged). Installing the must-work id means installing and scoring `vscode-containers`, not
  `vscode-docker` — its profile follows the docker stub's in `corpus-profiles.md` for that reason.
- **`PKief.material-icon-theme`** looks declarative but ships a real `main` (11 commands, `onStartupFinished`); its
  ADR-0029 "already bundled" status (licensing-policy.md §3.2) does not change that its `.js` still runs in the host.

## 6. Corrections to the original brief

| claim in the brief | verdict | evidence |
|---|---|---|
| Claude Code's "default target" is `alpine-arm64` | **Misleading, not a publisher default.** `GET /api/Anthropic/claude-code` with no target segment returns whichever `targetPlatform` record the registry happens to key first for that version — an artefact of API/DB ordering, not a chosen default. `meta.pyrefly` also returns `alpine-arm64` first and `ms-python.debugpy` returns `darwin-arm64` first for the same reason; it looks alphabetical, is not guaranteed to be, and must never be read as "the extension prefers Alpine" | cached `/api/{ns}/{name}` JSON, `research/corpus-notes.md` "Brief fact checks" |
| `linux-arm64` does not exist for Claude Code / is Alpine-only | **Wrong.** 2.1.282 publishes 8 targets including `linux-arm64` (glibc) alongside `alpine-arm64`/`alpine-x64` (musl); `linux-arm64` is the one this corpus selects (D7 target rule: `linux-arm64` > `universal` > fallback) | `downloads` map, `data/corpus.json` |
| Claude Code ships a `LICENSE` file | **Wrong — there is none.** The `.vsix` file list is `README.md`, `package.json`, `extension.js`, `claude-code-settings.schema.json`, `resources/`, `webview/`; no `files.license` on the registry record either. The only licence text available is the `package.json`/registry `license` string itself ("© Anthropic PBC. All rights reserved. Use is subject to the Legal Agreements outlined here: https://code.claude.com/docs/en/legal-and-compliance.") — quote that string, do not invent or assume a LICENSE file exists | `unzip -l`, cached API JSON |
| GitLens numbers in the brief (1165 commands, 61 `viewsWelcome`, 57 `submenus`, 1 `customEditors`, 1 `mcpServerDefinitionProviders`, `engines.vscode ^1.101.0`) | **Confirmed correct**, all fields | `data/corpus.json`, spot-checked against the unpacked `.vsix` in `research/corpus-notes.md` "Brief fact checks" |
| `PKief.material-icon-theme` is purely declarative | **Wrong** — see `## 5` above; it has a real `main` and 11 commands | `data/corpus.json` |

## 7. How to regenerate

Cache lives outside the repo at `/root/.cache/easyide-corpus/{api,vsix,x,scan,fetch-state.json}` (never inside `$WT`).

```sh
# 1. Fetch the corpus list + .vsix files (online first run; offline afterwards from cache, or CORPUS_OFFLINE=1 to force it)
node tools/vsx-audit/corpus-fetch.mjs

# 2. Scan manifests/bundles/native binaries -> docs/vsx-compat/data/{corpus,corpus-usage}.json
#    acorn is installed OUT OF TREE (tools/vsx-audit/package.json belongs to another script) and passed via env:
npm install --prefix "$SP/node_modules-corpus" acorn@8 acorn-walk@8
CORPUS_NODE_MODULES="$SP/node_modules-corpus/node_modules" node tools/vsx-audit/corpus-scan.mjs

# 3. Regenerate this file's and corpus-profiles.md's generated sections (zero deps, pure node:fs) from the two JSON files
node tools/vsx-audit/render-corpus.mjs
```

`render-corpus.mjs` only rewrites the text inside each marker pair (start/end HTML comments named `gen:start <id>` /
`gen:end <id>`); prose outside markers (this whole file's `## 1`, `## 4` narrative, `## 6`, `## 7`, and the
classification-rule prose in `## 2`) is untouched and must be updated by hand if the method changes.
