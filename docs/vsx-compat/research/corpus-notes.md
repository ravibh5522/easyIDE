# Corpus audit notes (agent CORPUS)

Data: `docs/vsx-compat/data/corpus.json` (156 extensions, 1.65 MB), `docs/vsx-compat/data/corpus-usage.json` (0.88 MB).
Scripts: `tools/vsx-audit/corpus-fetch.mjs`, `tools/vsx-audit/corpus-scan.mjs`, `tools/vsx-audit/lib/corpus-{http,manifest,js,native}.mjs`.
Re-run: `node tools/vsx-audit/corpus-fetch.mjs` (online the first time, then offline from `$CACHE/api`, or force it with `CORPUS_OFFLINE=1`), then
`npm install --prefix $SP/node_modules-corpus acorn@8 acorn-walk@8` and `CORPUS_NODE_MODULES=$SP/node_modules-corpus/node_modules node tools/vsx-audit/corpus-scan.mjs`.
Cache: `/root/.cache/easyide-corpus/{api,vsix,x,scan,fetch-state.json}`.

## Snapshot
| item | value |
|---|---|
| snapshotAt (first search request) | 2026-09-25T14:49:49.567Z |
| search pages | 2 (`https://open-vsx.org/api/-/search?sortBy=downloadCount&sortOrder=desc&size=100&offset={0,100}`), totalSize 18401 |
| ranks walked | 1..163 (150 code + 13 declarative) |
| corpus | 156 = 150 top code + 1 must-work declarative (ms-azuretools.vscode-docker, rank 34) + 5 deps/pack members (vscode.git, vscode.git-base, vscode.github-authentication, vscode.docker, vscode.yaml). All 22 must-work resolved; 21 of them are in the top 150 code list (lowest: usernamehw.errorlens, rank 102) |
| unresolved | `ms-python.vscode-pylance` (extensionPack member of ms-python.python): `https://open-vsx.org/api/ms-python/vscode-pylance` returns 404 `{"error":"Extension not found: ms-python.vscode-pylance"}` |
| total download weight (all 156) | 1,091,356,814 (top-150 code only: 1,083,067,550) |
| downloaded | 156 .vsix, 2,729,485,852 bytes (2.73 GB); unpacked 6,829,271,295 bytes; SHA-256 verified 156/156 against the registry `.sha256` files; `.sigzip` signature published for 156/156 (not verified) |
| targets chosen | linux-arm64 17, universal 138, fallback 1 (devsense.intelli-php-vscode -> linux-x64, noGlibcArm64) |
| API usage method | acorn 150 extensions, regex-only 2 (googlecloudtools.datacloud 47 MB, Continue.continue 56 MB main bundles, above the 40 MB parse limit), none 4 (3 declarative + cweijan.dbclient-jdbc which does not import vscode) |
| registry fields | `namespaceAccess` does NOT exist in the API response (keys seen: allVersions, averageRating, verified, publishedBy{loginName,provider}, downloads{target:url}, files, engines, ...); recorded as null. `verified` + `publishedBy` are recorded. Available targets for the latest version come from the `downloads` map (there is no `allTargetPlatformVersions` field on `/api/{ns}/{name}`) |

Declarative (no main/browser) in ranks 1..163, not counted in the 150:
| rank | id | downloads | kind |
|---|---|---|---|
| 22 | magicstack.MagicPython | 11,793,065 | grammar |
| 23 | EricSia.pythonsnippets3 | 11,742,319 | snippets |
| 28 | ms-toolsai.jupyter-keymap | 8,671,013 | keymap |
| 34 | ms-azuretools.vscode-docker 2.0.0 (must-work) | 6,814,911 | stub: extensionDependencies [ms-azuretools.vscode-containers] only |
| 47/92/121/152/155 | MS-CEINTL language packs zh-hans/ja/es/pt-BR/ko | 3.61M/1.45M/0.83M/0.60M/0.59M | languagePack |
| 111/112/146/159 | GraphQL.vscode-graphql-syntax, wingrunr21.vscode-ruby, vscode.json, ms-vscode.vscode-typescript-next | 0.95M/0.93M/0.64M/0.58M | grammar |
13 declarative, 49,195,363 downloads in total. PKief.material-icon-theme HAS a `main` (`./dist/extension/desktop/extension.cjs`, 193 KB, 11 commands), so it is a code extension, not declarative.

## Top 150 code extensions
| rank | id | downloads | version | licence | target | noGlibcArm64 | native binaries | engines.vscode | #prop |
|---|---|---|---|---|---|---|---|---|---|
| 1 | meta.pyrefly | 86,563,040 | 1.3.9001 | MIT | linux-arm64 |  | 1 (aarch64/glibcx1; 32MB) | ^1.103.0 | 1 |
| 2 | ms-python.debugpy | 60,064,855 | 2026.6.0 | MIT | linux-arm64 |  | 2 (x86-64/glibcx2; 3MB) | ^1.92.0 | 3 |
| 3 | ms-python.python | 59,121,980 | 2026.4.0 | MIT | universal |  | - | ^1.95.0 | 9 |
| 4 | Anthropic.claude-code | 52,395,010 | 2.1.282 | © Anthropic PBC. All rights reserved. Us | linux-arm64 |  | 2 (aarch64/glibcx2; 239MB) | ^1.94.0 | 0 |
| 5 | Shopify.ruby-lsp | 43,132,270 | 0.10.6 | MIT | universal |  | - | ^1.91.0 | 0 |
| 6 | redhat.java | 42,743,388 | 1.57.2026092508 | EPL-2.0 | linux-arm64 |  | 66 (aarch64/glibcx66; 37MB) | ^1.77.0 | 0 |
| 7 | ms-python.vscode-python-envs | 41,913,485 | 1.38.0 | ? | universal |  | - | ^1.110.0-20260204 | 3 |
| 8 | golang.Go | 40,903,961 | 0.56.1 | MIT | universal |  | - | ^1.90.0 | 0 |
| 9 | devsense.composer-php-vscode | 40,878,749 | 1.74.19317 | LicenseRef-LICENSE | universal |  | - | ^1.63.1 | 0 |
| 10 | devsense.profiler-php-vscode | 40,845,634 | 1.74.19317 | LicenseRef-LICENSE | universal |  | - | ^1.63.1 | 0 |
| 11 | devsense.phptools-vscode | 39,894,154 | 1.74.19317 | LicenseRef-LICENSE | linux-arm64 |  | 3 (aarch64/glibcx3; 50MB) | ^1.70.0 | 0 |
| 12 | vscjava.vscode-java-dependency | 39,653,408 | 0.27.6 | MIT | universal |  | - | ^1.95.0 | 0 |
| 13 | llvm-vs-code-extensions.vscode-clangd | 30,200,038 | 0.6.0 | MIT | universal |  | - | ^1.75.0 | 0 |
| 14 | vscjava.vscode-maven | 28,170,574 | 0.45.3 | MIT | universal |  | - | ^1.75.0 | 0 |
| 15 | vscjava.vscode-gradle | 27,225,448 | 3.18.0 | SEE LICENSE IN LICENSE.md | universal |  | - | ^1.76.0 | 0 |
| 16 | vscjava.vscode-java-debug | 26,023,408 | 0.59.0 | SEE LICENSE IN LICENSE.txt | universal |  | - | ^1.95.0 | 0 |
| 17 | vscjava.vscode-java-test | 25,821,235 | 0.46.0 | ? | universal |  | - | ^1.88.0 | 0 |
| 18 | vscjava.vscode-java-pack | 21,954,832 | 0.31.1 | MIT | universal |  | - | ^1.74.0 | 0 |
| 19 | eamodio.gitlens | 16,867,728 | 2026.9.250515 | %gitlens.license% | universal |  | - | ^1.101.0 | 0 |
| 20 | ms-azuretools.vscode-containers | 14,319,357 | 2.4.5 | SEE LICENSE IN LICENSE.md | universal |  | - | ^1.105.0 | 0 |
| 21 | openai.chatgpt | 12,034,237 | 26.5908.31748 | SEE LICENSE IN LICENSE.md | linux-arm64 |  | 6 (aarch64/static/nonex3, aarch64/glibcx3; 301MB) | ^1.96.2 | 2 |
| 24 | ms-toolsai.jupyter | 10,336,000 | 2025.9.1 | MIT | universal |  | 16 (None/pex4, None/machox3, arm/glibcx2; 17MB) | ^1.105.0 | 13 |
| 25 | Vue.volar | 10,191,124 | 3.3.11 | ? | universal |  | - | ^1.88.0 | 0 |
| 26 | ms-toolsai.jupyter-renderers | 9,755,102 | 1.3.0 | MIT | universal |  | - | ^1.95.0 | 1 |
| 27 | esbenp.prettier-vscode | 9,225,853 | 12.4.0 | MIT | universal |  | - | ^1.101.0 | 0 |
| 29 | ms-toolsai.vscode-jupyter-cell-tags | 8,082,240 | 0.1.9 | ? | universal |  | - | ^1.88.0 | 0 |
| 30 | ms-toolsai.vscode-jupyter-slideshow | 8,035,138 | 0.1.6 | ? | universal |  | - | ^1.88.0 | 0 |
| 31 | devsense.intelli-php-vscode | 7,852,858 | 0.12.17700 | LicenseRef-LICENSE | linux-x64 | Y | 3 (x86-64/glibcx3; 34MB) | ^1.70.0 | 0 |
| 32 | redhat.vscode-yaml | 7,814,452 | 1.25.2026092308 | MIT | universal |  | - | ^1.63.0 | 0 |
| 33 | rust-lang.rust-analyzer | 7,029,060 | 0.4.3061 | MIT OR Apache-2.0 | linux-arm64 |  | 1 (aarch64/glibcx1; 41MB) | ^1.93.0 | 0 |
| 35 | saoudrizwan.claude-dev | 6,720,377 | 4.1.21 | Apache-2.0 | universal |  | - | ^1.101.0 | 0 |
| 36 | GitHub.vscode-pull-request-github | 6,554,527 | 0.166.1 | MIT | universal |  | - | ^1.137.0 | 32 |
| 37 | PKief.material-icon-theme | 6,080,100 | 5.38.1 | MIT | universal |  | - | ^1.55.0 | 0 |
| 38 | dbaeumer.vscode-eslint | 5,997,165 | 3.0.34 | MIT | universal |  | - | ^1.90.0 | 0 |
| 39 | Dart-Code.dart-code | 5,616,619 | 3.143.20260901 | SEE LICENSE IN LICENSE | universal |  | - | ^1.101.0 | 0 |
| 40 | jlcodes.antigravity-cockpit | 4,995,841 | 2.1.52 | ? | universal |  | - | ^1.90.0 | 0 |
| 41 | Dart-Code.flutter | 4,829,530 | 3.143.20260901 | SEE LICENSE IN LICENSE | universal |  | - | ^1.101.0 | 0 |
| 42 | GitHub.vscode-github-actions | 4,411,985 | 0.32.3 | MIT | universal |  | - | ^1.72.0 | 0 |
| 43 | quarto.quarto | 4,352,736 | 1.138.0 | MIT | universal |  | - | ^1.75.0 | 0 |
| 44 | kilocode.kilo-code | 4,206,777 | 7.8.0 | MIT | linux-arm64 |  | 4 (aarch64/static/nonex3, aarch64/glibcx1; 203MB) | ^1.105.1 | 0 |
| 45 | Google.geminicodeassist | 4,007,784 | 2.100.0 | SEE LICENSE IN LICENSE | universal |  | - | ^1.97.0 | 0 |
| 46 | anyscalecompute.anyscale-workspaces | 3,952,269 | 0.2.45 | ? | universal |  | - | ^1.66.0 | 0 |
| 48 | amazonwebservices.aws-toolkit-vscode | 3,525,260 | 4.15.0 | Apache-2.0 | universal |  | - | ^1.83.0 | 0 |
| 49 | charliermarsh.ruff | 3,477,732 | 2026.84.0 | MIT | linux-arm64 |  | 1 (aarch64/glibcx1; 22MB) | ^1.75.0 | 0 |
| 50 | mechatroner.rainbow-csv | 3,195,691 | 3.24.1 | MIT | universal |  | - | ^1.95.0 | 0 |
| 51 | ms-vscode.js-debug | 3,130,719 | 1.140.0 | MIT | universal |  | 2 (None/pex2; 0MB) | ^1.80.0 | 4 |
| 52 | googlecloudtools.datacloud | 3,035,780 | 0.11.0 | SEE LICENSE IN LICENSE | universal |  | - | ^1.97.0 | 0 |
| 53 | ritwickdey.LiveServer | 2,963,566 | 5.7.10 | MIT | universal |  | 1 (None/machox1; 0MB) | ^1.51.0 | 0 |
| 54 | posit.shiny | 2,912,829 | 1.4.3 | ? | universal |  | - | ^1.96.0 | 0 |
| 55 | Angular.ng-template | 2,819,912 | 22.1.1 | MIT | universal |  | - | ^1.74.3 | 0 |
| 56 | Codeium.windsurfPyright | 2,763,442 | 1.29.6 | SEE LICENSE IN LICENSE.txt | universal |  | - | ^1.99.0 | 0 |
| 57 | rangav.vscode-thunder-client | 2,706,986 | 2.41.3 | ? | universal |  | - | ^1.85.0 | 0 |
| 58 | GitLab.gitlab-workflow | 2,607,128 | 6.90.3 | MIT | universal |  | 1 (x86-64/static/nonex1; 6MB) | ^1.92.2 | 0 |
| 59 | bradlc.vscode-tailwindcss | 2,492,674 | 0.16.0 | MIT | universal |  | - | ^1.67.0 | 0 |
| 60 | ms-vscode.powershell | 2,442,965 | 2025.4.0 | SEE LICENSE IN LICENSE.txt | universal |  | 151 (None/pex151; 14MB) | ^1.101.0 | 0 |
| 61 | detachhead.basedpyright | 2,432,868 | 1.40.1 | MIT | universal |  | - | ^1.101.0 | 0 |
| 62 | anysphere.pyright | 2,288,633 | 1.1.327 | MIT | universal |  | - | ^1.78.0 | 0 |
| 63 | hashicorp.terraform | 2,287,737 | 2.40.0 | MPL-2.0 | linux-arm64 |  | 1 (aarch64/static/nonex1; 42MB) | ^1.92.2 | 0 |
| 64 | bmewburn.vscode-intelephense-client | 2,197,917 | 1.18.5 | SEE LICENSE IN LICENSE.txt | universal |  | - | ^1.91.0 | 0 |
| 65 | EditorConfig.EditorConfig | 2,189,849 | 0.18.2 | MIT | universal |  | - | ^1.100.0 | 0 |
| 66 | shd101wyy.markdown-preview-enhanced | 2,145,913 | 0.8.36 | NCSA | universal |  | - | ^1.82.0 | 0 |
| 67 | ms-vscode.cmake-tools | 2,132,483 | 1.24.42 | MIT | universal |  | 1 (None/pex1; 0MB) | ^1.88.0 | 0 |
| 68 | amazonwebservices.amazon-q-vscode | 2,124,416 | 2.7.0 | Apache-2.0 | universal |  | 3 (None/pex3; 1MB) | ^1.83.0 | 0 |
| 69 | svelte.svelte-vscode | 2,105,343 | 110.3.1 | MIT | universal |  | - | ^1.82.0 | 0 |
| 70 | ms-vscode.js-debug-companion | 2,095,352 | 1.1.3 | MIT | universal |  | - | ^1.90.0 | 0 |
| 71 | ms-vscode.live-server | 2,086,595 | 0.4.20 | ? | universal |  | - | ^1.109.0 | 0 |
| 72 | Prisma.prisma-insider | 2,074,001 | 31.12.11 | Apache-2.0 | universal |  | - | ^1.104.0 | 0 |
| 73 | cweijan.vscode-office | 2,069,323 | 4.2.0 | ? | universal |  | - | ^1.64.0 | 0 |
| 74 | synedra.auto-run-command | 2,058,572 | 1.6.1 | MIT | universal |  | - | ^1.60.0 | 0 |
| 75 | jmxgrog.vscode-nuget-package-manager | 2,043,987 | 1.1.6 | MIT | universal |  | - | ^1.10.0 | 0 |
| 76 | ms-vscode.vscode-js-profile-table | 2,038,083 | 1.0.11 | MIT | universal |  | - | ^1.74.0 | 0 |
| 77 | ms-dotnettools.vscode-dotnet-runtime | 1,973,610 | 3.2.0 | MIT | universal |  | - | ^1.101.0 | 0 |
| 78 | RooVeterinaryInc.roo-cline | 1,970,644 | 3.54.0 | ? | universal |  | - | ^1.84.0 | 0 |
| 79 | REditorSupport.r | 1,946,555 | 2.8.8 | SEE LICENSE IN LICENSE | universal |  | - | ^1.75.0 | 0 |
| 80 | streetsidesoftware.code-spell-checker | 1,937,197 | 4.9.3 | GPL-3.0-or-later | universal |  | - | ^1.104.0 | 0 |
| 81 | vscodevim.vim | 1,906,591 | 1.32.4 | MIT | universal |  | - | ^1.74.0 | 0 |
| 82 | SonarSource.sonarlint-vscode | 1,869,155 | 5.10.0 | SEE LICENSE IN LICENSE.txt | universal |  | - | ^1.99.3 | 0 |
| 83 | MermaidChart.vscode-mermaid-chart | 1,846,433 | 2.8.1 | ? | universal |  | - | ^1.77.0 | 1 |
| 84 | VMware.vscode-spring-boot | 1,813,829 | 2.5.2026092400 | EPL-1.0 | universal |  | - | ^1.92.0 | 0 |
| 85 | DavidAnson.vscode-markdownlint | 1,767,395 | 0.62.1 | MIT | universal |  | - | ^1.97.0 | 0 |
| 86 | posit.publisher | 1,733,334 | 2.13.11 | MIT | universal |  | - | ^1.105.0 | 0 |
| 87 | Continue.continue | 1,642,136 | 2.1.0 | Apache-2.0 | linux-arm64 |  | 17 (aarch64/glibcx6, x86-64/glibcx4, None/pex4; 193MB) | ^1.70.0 | 0 |
| 88 | tomoki1207.pdf | 1,627,109 | 1.2.2 | ? | universal |  | - | ^1.46.0 | 0 |
| 89 | Google.gemini-cli-vscode-ide-companion | 1,574,966 | 0.20.0 | LICENSE | universal |  | - | ^1.99.0 | 0 |
| 90 | redhat.vscode-xml | 1,570,337 | 0.29.2026091508 | EPL-2.0 | linux-arm64 |  | 1 (aarch64/glibcx1; 50MB) | ^1.67.0 | 0 |
| 91 | vscode-icons-team.vscode-icons | 1,464,158 | 12.19.0 | MIT | universal |  | - | ^1.82.0 | 0 |
| 93 | Prisma.prisma | 1,443,306 | 31.12.10 | Apache-2.0 | universal |  | - | ^1.104.0 | 0 |
| 94 | highagency.pencildev | 1,440,512 | 0.6.73 | SEE LICENSE IN LICENSE | universal |  | 7 (None/machox2, None/pex2, x86-64/static/nonex1; 50MB) | ^1.100.0 | 0 |
| 95 | cweijan.vscode-mysql-client2 | 1,347,164 | 9.0.2 | ? | universal |  | - | ^1.68.0 | 0 |
| 96 | ms-python.black-formatter | 1,322,934 | 2025.2.0 | MIT | universal |  | - | ^1.82.0 | 0 |
| 97 | vadimcn.vscode-lldb | 1,304,154 | 1.12.3 | MIT | universal |  | - | ^1.61.0 | 0 |
| 98 | ms-kubernetes-tools.vscode-kubernetes-tools | 1,206,462 | 1.4.1 | Apache-2.0 | universal |  | - | ^1.110.0 | 0 |
| 99 | muhammad-sammy.csharp | 1,128,412 | 2.145.21-g154a82fd27 | SEE LICENSE IN RuntimeLicenses/license.t | linux-arm64 |  | 646 (None/pex642, aarch64/glibcx4; 175MB) | ^1.106.0 | 0 |
| 100 | mhutchie.git-graph | 1,120,180 | 1.30.0 | ? | universal |  | - | ^1.38.0 | 0 |
| 101 | mtxr.sqltools | 1,091,678 | 0.28.6 | MIT | universal |  | - | ^1.78.0 | 0 |
| 102 | usernamehw.errorlens | 1,060,030 | 3.28.0 | MIT | universal |  | - | ^1.107.0 | 0 |
| 103 | astro-build.astro-vscode | 1,053,439 | 2.16.20 | MIT | linux-arm64 |  | - | ^1.101.0 | 0 |
| 104 | huijbzhou.githd | 1,044,298 | 2.5.7 | MIT | universal |  | - | ^1.91.0 | 0 |
| 105 | ms-pyright.pyright | 1,036,974 | 1.1.402 | MIT | universal |  | - | ^1.99.0 | 0 |
| 106 | NexrallCode.nexrall-code-vscode | 1,012,368 | 1.0.132 | ? | universal |  | - | ^1.90.0 | 0 |
| 107 | formulahendry.code-runner | 993,974 | 0.12.2 | ? | universal |  | - | ^1.56.0 | 0 |
| 108 | vscode.npm | 987,665 | 1.95.3 | SEE LICENSE IN LICENSE-vscode.txt | universal |  | - | 0.10.0 | 1 |
| 109 | ajsqnhort.include-autocomplete | 984,004 | 0.0.4 | MIT | universal |  | - | ^1.5.0 | 0 |
| 110 | eclipse-cdt.cdt-gdb-vscode | 960,247 | 2.9.1 | EPL-2.0 | universal |  | 17 (x86-64/glibcx3, None/pex3, arm/static/nonex2; 2MB) | ^1.78.0 | 0 |
| 113 | kade.kade | 921,173 | 4.0.4 | ? | universal |  | - | ^1.84.0 | 0 |
| 114 | qwtel.sqlite-viewer | 919,758 | 26.9.1 | LICENSE.md | linux-arm64 |  | 1 (aarch64/glibcx1; 4MB) | ^1.83.1 | 0 |
| 115 | Alibaba-Cloud.tongyi-lingma | 861,824 | 2.6.10 | ? | universal |  | - | ^1.68.0 | 0 |
| 116 | ms-python.isort | 859,870 | 2025.0.0 | MIT | universal |  | - | ^1.74.0 | 0 |
| 117 | dsznajder.es7-react-js-snippets | 859,469 | 4.4.3 | MIT | universal |  | - | ^1.60.0 | 0 |
| 118 | zhuangtongfa.material-theme | 854,751 | 3.20.2 | MIT | universal |  | - | ^1.76.0 | 0 |
| 119 | firsttris.vscode-jest-runner | 838,525 | 0.4.148 | MIT | universal |  | - | ^1.100.0 | 0 |
| 120 | James-Yu.latex-workshop | 833,275 | 10.19.0 | MIT | universal |  | - | ^1.114.0 | 0 |
| 122 | jeanp413.open-remote-ssh | 829,326 | 0.3.1 | ? | universal |  | - | ^1.70.2 | 2 |
| 123 | googlecloudtools.firebase-dataconnect-vscode | 821,392 | 2.4.3 | ? | universal |  | 1 (None/machox1; 0MB) | ^1.69.0 | 0 |
| 124 | ms-vscode.node-debug2 | 810,943 | 1.43.0 | MIT | universal |  | - | ^1.60.0-insider | 0 |
| 125 | dbcode.dbcode | 802,679 | 1.38.7 | SEE LICENSE IN LICENSE | universal |  | 8 (x86-64/glibcx3, None/machox2, None/pex2; 3MB) | ^1.101.0 | 0 |
| 126 | vitest.explorer | 799,631 | 1.52.0 | MIT | universal |  | - | ^1.88.0 | 0 |
| 127 | qwenlm.qwen-code-vscode-ide-companion | 789,501 | 0.24.5 | LICENSE | universal |  | 6 (None/machox2, x86-64/glibcx1, aarch64/static/nonex1; 31MB) | ^1.96.0 | 0 |
| 128 | bierner.markdown-mermaid | 786,516 | 1.32.1 | MIT | universal |  | - | ^1.100.0 | 0 |
| 129 | atlassian.atlascode | 771,563 | 4.1.200 | SEE LICENSE IN LICENSE | universal |  | - | ^1.77.0 | 0 |
| 130 | ecmel.vscode-html-css | 767,502 | 2.0.14 | MIT | universal |  | - | ^1.86.0 | 0 |
| 131 | xdebug.php-debug | 752,681 | 1.40.2 | MIT | universal |  | - | ^1.66.1 | 0 |
| 132 | cweijan.dbclient-jdbc | 718,630 | 1.4.2 | ? | universal |  | - | ^1.60.0 | 0 |
| 133 | Oracle.oracle-java | 717,250 | 26.0.2 | Apache 2.0 | universal |  | 32 (None/pex17, None/machox6, aarch64/glibcx3; 4MB) | ^1.84.0 | 0 |
| 134 | stylelint.vscode-stylelint | 705,370 | 2.2.1 | MIT | universal |  | - | >=1.103.0 | 0 |
| 135 | henrikdev.ag-quota | 688,151 | 1.1.1 | MIT | universal |  | - | ^1.90.0 | 0 |
| 136 | Google.colab | 686,746 | 0.9.6 | ? | universal |  | - | ^1.99.3 | 0 |
| 137 | timonwong.shellcheck | 677,357 | 0.40.1 | MIT | linux-arm64 |  | 1 (aarch64/static/nonex1; 55MB) | ^1.100.0 | 0 |
| 138 | redhat.ansible | 674,807 | 26.8.2 | MIT | universal |  | - | ^1.91.0 | 0 |
| 139 | fwcd.kotlin | 674,378 | 0.2.36 | MIT | universal |  | - | ^1.52.0 | 0 |
| 140 | Toaock.vscode-css-custom-properties | 670,732 | 0.0.5 | MIT | universal |  | - | ^1.56.0 | 0 |
| 141 | yzhang.markdown-all-in-one | 665,288 | 3.6.2 | MIT | universal |  | - | ^1.77.0 | 0 |
| 142 | nrwl.angular-console | 665,081 | 18.101.1 | MIT | universal |  | - | ^1.99.3 | 0 |
| 143 | coderabbit.coderabbit-vscode | 656,044 | 0.21.8 | See LICENSE | universal |  | - | ^1.93.1 | 0 |
| 144 | tamasfe.even-better-toml | 649,892 | 0.21.2 | SEE LICENSE IN LICENSE.md | universal |  | - | ^1.90.0 | 0 |
| 145 | donjayamanne.githistory | 643,456 | 0.6.20 | MIT | universal |  | - | ^1.76.0 | 0 |
| 147 | k--kato.intellij-idea-keybindings | 635,105 | 1.7.8 | MIT | universal |  | - | ^1.138.0 | 0 |
| 148 | salesforce.salesforcedx-vscode-core | 619,905 | 67.23.1 | BSD-3-Clause | universal |  | - | ^1.90.0 | 0 |
| 149 | TabNine.tabnine-vscode | 610,468 | 3.346.8 | License at https://tabnine.com/eula | universal |  | 6 (None/machox2, None/pex2, aarch64/muslx1; 10MB) | ^1.50.0 | 0 |
| 150 | malloydata.malloy-vscode | 607,931 | 0.3.1789753698 | MIT | linux-arm64 |  | 2 (aarch64/glibcx2; 63MB) | ^1.82.0 | 0 |
| 151 | yossisa.cursor-usage | 603,466 | 1.3.0 | MIT | universal |  | - | ^1.74.0 | 0 |
| 153 | aaron-bond.better-comments | 592,632 | 3.0.2 | MIT | universal |  | - | ^1.65.0 | 0 |
| 154 | vscode.json-language-features | 592,510 | 1.95.3 | SEE LICENSE IN LICENSE-vscode.txt | universal |  | - | ^1.77.0 | 1 |
| 156 | waderyan.gitblame | 590,612 | 13.1.0 | MIT | universal |  | - | >=1.100.0 | 0 |
| 157 | formulahendry.auto-rename-tag | 590,026 | 0.1.10 | MIT | universal |  | - | ^1.41.1 | 0 |
| 158 | kombai.kombai | 587,470 | 2.0.128 | SEE LICENSE IN LICENSE | universal |  | 11 (None/machox4, None/pex4, x86-64/glibcx2; 1MB) | ^1.84.1 | 0 |
| 160 | salesforce.salesforcedx-vscode-apex | 568,249 | 67.23.1 | BSD-3-Clause | universal |  | - | ^1.90.0 | 0 |
| 161 | pokey.cursorless | 566,390 | 1.1.1865 | MIT | universal |  | - | ^1.98.0 | 0 |
| 162 | ms-vscode.makefile-tools | 564,693 | 0.12.17 | SEE LICENSE IN LICENSE.txt | universal |  | - | ^1.74.0 | 0 |
| 163 | yandeu.five-server | 563,198 | 0.4.0 | SEE LICENSE IN LICENSE | universal |  | - | ^1.90.0 | 0 |

## Must-work and dependency profiles (top 15 APIs by call sites; all from corpus.json)
### meta.pyrefly 1.3.9001 (86,563,040 dl, rank 1)
- target linux-arm64 (alpine-arm64, alpine-x64, darwin-arm64, darwin-x64, linux-arm64, linux-armhf, linux-x64, win32-arm64, win32-x64); vsix 15.2MB, unpacked 32.2MB, main ./dist/extension 0.38MB, js files 2; engines {'vscode': '^1.103.0'}; licence: MIT
- activation: ['onLanguage:python', 'onNotebook:jupyter-notebook'] (+7 implicit)
- contributes: configuration:22, commands:6, configurationDefaults:4, languages:1, semanticTokenScopes:1; webviewViews 0, whenKeys 0
- top APIs (acorn): Uri 21, languages.match 13, workspace.getConfiguration 13, EventEmitter 11, DiagnosticSeverity 10, MarkdownString 10, window.showErrorMessage 10, CodeActionKind 9, commands.registerCommand 7, extensions.getExtension 7, window.activeTextEditor 7, window.showInformationMessage 6, workspace.getWorkspaceFolder 6, workspace.textDocuments 6, workspace.workspaceFolders 6
- native: 1 (aarch64/glibcx1; 32MB); wasm 0; builtins: net:1, child_process:5; proposals: ['editorHoverVerbosityLevel']
- blockers: ships 1 aarch64 native binaries (0 node addons) - needs native loading in proot; proposed API: editorHoverVerbosityLevel
### ms-python.debugpy 2026.6.0 (60,064,855 dl, rank 2) dependencyOf ms-python.python
- target linux-arm64 (darwin-arm64, darwin-x64, linux-arm64, linux-armhf, linux-x64, win32-arm64, win32-x64); vsix 4.7MB, unpacked 20.7MB, main ./dist/extension.js 1.13MB, js files 1; engines {'vscode': '^1.92.0'}; licence: MIT
- activation: ['onDebugInitialConfigurations', 'onDebugDynamicConfigurations:debugpy', 'onDebugResolve:debugpy', 'onLanguage:python'] (+6 implicit)
- contributes: menus:8, commands:5, configuration:2, debuggers:1, debugVisualizers:1, viewsWelcome:1; webviewViews 0, whenKeys 8
- top APIs (acorn): l10n.t 81, Uri 10, ConfigurationTarget 9, extensions.getExtension 7, QuickInputButtons 4, debug.registerDebugAdapterTrackerFactory 3, debug.startDebugging 3, Range 3, ThemeIcon 3, window.activeTextEditor 3, window.showTextDocument 3, workspace.workspaceFolders 3, commands.executeCommand 2, commands.registerCommand 2, debug.onDidReceiveDebugSessionCustomEvent 2
- native: 2 (x86-64/glibcx2; 3MB); wasm 0; builtins: child_process:1, http:2, https:2, net:1; proposals: ['portsAttributes', 'debugVisualization', 'contribViewsWelcome']
- blockers: ships ELF native binaries but none for aarch64-glibc (x86-64/glibc); proposed API: portsAttributes, debugVisualization, contribViewsWelcome; contributes 1 debuggers (debug.dap out of scope)
### ms-python.python 2026.4.0 (59,121,980 dl, rank 3)
- target universal (universal); vsix 6.8MB, unpacked 24.2MB, main ./out/client/extension 2.87MB, js files 22; engines {'vscode': '^1.95.0'}; licence: MIT
- activation: ['onDebugInitialConfigurations', 'onLanguage:python', 'onDebugResolve:python', 'onCommand:python.copilotSetupTests', 'workspaceContains:mspythonconfig.json', 'workspaceContains:pyproject.toml', 'workspaceContains:Pipfile', 'workspaceContains:setup.py']... (+37 implicit)
- contributes: configuration:41, menus:38, commands:23, languages:6, languageModelTools:6, breakpoints:5, keybindings:4, jsonValidation:3, yamlValidation:3, walkthroughs:2, submenus:2, problemMatchers:1; webviewViews 0, whenKeys 36
- top APIs (acorn): l10n.t 314, Uri 93, EventEmitter 64, workspace.workspaceFolders 52, ConfigurationTarget 46, DiagnosticSeverity 27, Range 16, languages.match 14, Position 14, CancellationError 13, CancellationTokenSource 13, workspace.getConfiguration 13, workspace.getWorkspaceFolder 13, CodeActionKind 11, Disposable 11
- native: -; wasm 0; builtins: child_process:6, http:3, https:3, net:3, worker_threads:5; proposals: ['contribEditorContentMenu', 'quickPickSortByLabel', 'testObserver', 'quickPickItemTooltip', 'terminalDataWriteEvent', 'terminalExecuteCommandEvent', 'codeActionAI', 'notebookReplDocument', 'notebookVariableProvider']
- blockers: proposed API: contribEditorContentMenu, quickPickSortByLabel, testObserver, quickPickItemTooltip, terminalDataWriteEvent, terminalExecuteCommandEvent, codeActionAI, notebookReplDocument, notebookVariableProvider; contributes 1 debuggers (debug.dap out of scope); uses chat/lm API (lm.registerTool, LanguageModelTextPart, LanguageModelToolResult, lm.invokeTool); contributes chat/LM/MCP points
### Anthropic.claude-code 2.1.282 (52,395,010 dl, rank 4)
- target linux-arm64 (alpine-arm64, alpine-x64, darwin-arm64, darwin-x64, linux-arm64, linux-x64, win32-arm64, win32-x64); vsix 111.5MB, unpacked 249.1MB, main ./extension.js 3.07MB, js files 2; engines {'vscode': '^1.94.0'}; licence: © Anthropic PBC. All rights reserved. Use is subject to the Legal Agreements outlined here: https://code.claude.com/docs/en/legal-and-compliance.
- activation: ['onStartupFinished', 'onWebviewPanel:claudeVSCodePanel'] (+35 implicit)
- contributes: commands:31, menus:28, configuration:20, keybindings:9, viewsContainers:3, views:3, jsonValidation:1, walkthroughs:1; webviewViews 3, whenKeys 19
- top APIs (acorn): commands.executeCommand 52, Uri 43, commands.registerCommand 32, window.tabGroups 23, workspace.getConfiguration 23, workspace.workspaceFolders 22, window.showInformationMessage 18, window.showErrorMessage 12, workspace.openTextDocument 12, Range 11, window.activeTextEditor 11, EventEmitter 10, workspace.onDidChangeConfiguration 9, TabInputTextDiff 8, ViewColumn 8
- native: 2 (aarch64/glibcx2; 239MB); wasm 0; builtins: http:7, https:4, child_process:26, net:3; proposals: -
- blockers: ships 2 aarch64 native binaries (1 node addons) - needs native loading in proot
### Shopify.ruby-lsp 0.10.6 (43,132,270 dl, rank 5)
- target universal (universal); vsix 0.2MB, unpacked 0.8MB, main ./out/extension.js 0.55MB, js files 1; engines {'vscode': '^1.91.0'}; licence: MIT
- activation: ['workspaceContains:Gemfile.lock', 'workspaceContains:gems.locked'] (+27 implicit)
- contributes: commands:22, configuration:17, menus:6, grammars:3, languages:3, configurationDefaults:3, chatParticipants:1, views:1, breakpoints:1, debuggers:1, snippets:1; webviewViews 0, whenKeys 6
- top APIs (acorn): Uri 137, workspace.getConfiguration 40, workspace.fs 35, commands.registerCommand 30, commands.executeCommand 25, EventEmitter 25, window.showErrorMessage 15, window.showInformationMessage 15, languages.match 14, window.activeTextEditor 13, workspace.workspaceFolders 13, Range 12, DiagnosticSeverity 10, Position 10, CodeActionKind 9
- native: -; wasm 0; builtins: child_process:6, net:3; proposals: -
- blockers: contributes 1 debuggers (debug.dap out of scope); uses chat/lm API (LanguageModelChatMessage, chat.createChatParticipant, ChatRequestTurn, ChatResponseMarkdownPart, ChatResponseTurn, ...); contributes chat/LM/MCP points
### redhat.java 1.57.2026092508 (42,743,388 dl, rank 6)
- target linux-arm64 (darwin-arm64, darwin-x64, linux-arm64, linux-x64, universal, win32-arm64, win32-x64); vsix 132.4MB, unpacked 192.4MB, main ./dist/extension 1.71MB, js files 15; engines {'vscode': '^1.77.0'}; licence: EPL-2.0
- activation: ['workspaceContains:pom.xml', 'workspaceContains:*/pom.xml', 'workspaceContains:build.gradle', 'workspaceContains:*/build.gradle', 'workspaceContains:settings.gradle', 'workspaceContains:*/settings.gradle', 'workspaceContains:build.gradle.kts', 'workspaceContains:*/build.gradle.kts']... (+40 implicit)
- contributes: configuration:137, commands:36, menus:35, semanticTokenModifiers:8, grammars:8, keybindings:6, semanticTokenTypes:5, javaShortcuts:4, languages:3, javaBuildFilePatterns:2, javaBuildTools:2, customEditors:1; webviewViews 0, whenKeys 22
- top APIs (acorn): Uri 103, commands.executeCommand 82, commands.registerCommand 73, workspace.getConfiguration 58, window.activeTextEditor 47, EventEmitter 33, CodeActionKind 31, window.showErrorMessage 29, workspace.workspaceFolders 29, window.showInformationMessage 24, window.showWarningMessage 24, window.showQuickPick 23, ViewColumn 16, ConfigurationTarget 15, languages.match 14
- native: 66 (aarch64/glibcx66; 37MB); wasm 0; builtins: child_process:3, http:1, https:3, net:1; proposals: -
- blockers: ships 66 aarch64 native binaries (0 node addons) - needs native loading in proot
### ms-python.vscode-python-envs 1.38.0 (41,913,485 dl, rank 7) dependencyOf ms-python.python
- target universal (universal); vsix 1.5MB, unpacked 3.6MB, main ./dist/extension.js 0.99MB, js files 4; engines {'vscode': '^1.110.0-20260204'}; licence: None
- activation: ['onLanguage:python'] (+38 implicit)
- contributes: menus:69, commands:35, configuration:10, views:2, viewsContainers:1, taskDefinitions:1; webviewViews 0, whenKeys 12
- top APIs (acorn): l10n.t 331, Uri 150, EventEmitter 44, ConfigurationTarget 38, ProgressLocation 32, QuickInputButtons 31, ThemeIcon 24, Disposable 20, window.showErrorMessage 17, QuickPickItemKind 15, TreeItem 15, TreeItemCollapsibleState 14, commands.executeCommand 12, CancellationError 11, MarkdownString 11
- native: -; wasm 0; builtins: child_process:2, https:1, net:1; proposals: ['terminalShellEnv', 'terminalDataWriteEvent', 'taskExecutionTerminal']
- blockers: proposed API: terminalShellEnv, terminalDataWriteEvent, taskExecutionTerminal
### golang.Go 0.56.1 (40,903,961 dl, rank 8)
- target universal (universal); vsix 0.6MB, unpacked 3.0MB, main ./dist/goMain.js 1.87MB, js files 3; engines {'vscode': '^1.90.0', 'node': '>=16.14.2'}; licence: MIT
- activation: ['onLanguage:go', 'onLanguage:go.sum', 'onLanguage:gotmpl', 'onLanguage:go.asm', 'onDebugInitialConfigurations', 'onDebugResolve:go', 'onWebviewPanel:welcomeGo'] (+77 implicit)
- contributes: commands:67, configuration:67, menus:39, languages:6, grammars:3, views:3, snippets:1, configurationDefaults:1, breakpoints:1, debuggers:1, taskDefinitions:1; webviewViews 0, whenKeys 31
- top APIs (acorn): window.showInformationMessage 70, window.showErrorMessage 69, window.activeTextEditor 64, Uri 54, commands.executeCommand 43, ThemeIcon 28, workspace.workspaceFolders 27, window.showWarningMessage 23, EventEmitter 19, Range 18, CodeLens 15, workspace.getConfiguration 15, languages.match 14, window.showInputBox 14, window.showQuickPick 14
- native: -; wasm 0; builtins: net:8, http:3, https:2, child_process:27; proposals: -
- blockers: contributes 1 debuggers (debug.dap out of scope)
### llvm-vs-code-extensions.vscode-clangd 0.6.0 (30,200,038 dl, rank 13)
- target universal (universal); vsix 0.6MB, unpacked 1.4MB, main ./out/bundle 1.04MB, js files 1; engines {'vscode': '^1.75.0'}; licence: MIT
- activation: ['onLanguage:c', 'onLanguage:cpp', 'onLanguage:cuda-cpp', 'onLanguage:objective-c', 'onLanguage:objective-cpp'] (+21 implicit)
- contributes: configuration:17, commands:17, menus:12, views:3, keybindings:2, languages:1, colors:1; webviewViews 0, whenKeys 13
- top APIs (acorn): Uri 25, commands.registerCommand 19, workspace.getConfiguration 18, commands.executeCommand 17, window.showInformationMessage 17, EventEmitter 14, languages.match 14, DiagnosticSeverity 10, CodeActionKind 9, window.activeTextEditor 9, window.showErrorMessage 9, workspace.workspaceFolders 9, ConfigurationTarget 7, workspace.rootPath 7, TreeItemCollapsibleState 6
- native: -; wasm 0; builtins: net:1, child_process:3, http:1, https:1; proposals: -
- blockers: -
### eamodio.gitlens 2026.9.250515 (16,867,728 dl, rank 19)
- target universal (universal); vsix 4.7MB, unpacked 18.8MB, main ./dist/gitlens.js 2.88MB, js files 32; engines {'node': '>= 24', 'pnpm': '>= 11.0.0', 'vscode': '^1.101.0'}; licence: %gitlens.license%
- activation: ['onFileSystem:gitlens', 'onStartupFinished', 'onTerminal:*', 'onUri', 'onWebviewPanel:gitlens.graph', 'onWebviewPanel:gitlens.patchDetails', 'onWebviewPanel:gitlens.settings', 'onWebviewPanel:gitlens.timeline'] (+1189 implicit)
- contributes: menus:3450, commands:1165, configuration:466, icons:94, colors:76, viewsWelcome:61, keybindings:59, submenus:57, views:21, viewsContainers:4, configurationDefaults:2, resourceLabelFormatters:2; webviewViews 5, whenKeys 274
- top APIs (acorn): l10n.t 3677, Uri 279, ThemeIcon 187, window.showWarningMessage 168, window.showErrorMessage 133, TreeItemCollapsibleState 97, Disposable 92, window.showInformationMessage 86, EventEmitter 75, workspace.fs 69, TreeItem 64, ThemeColor 56, ProgressLocation 54, window.withProgress 53, env.clipboard 43
- native: -; wasm 0; builtins: child_process:1, http:1, https:1, worker_threads:2; proposals: -
- blockers: uses chat/lm API (LanguageModelChatMessage, LanguageModelTextPart, LanguageModelToolCallPart, lm.registerMcpServerDefinitionProvider, lm.selectChatModels, ...); contributes chat/LM/MCP points
### ms-azuretools.vscode-containers 2.4.5 (14,319,357 dl, rank 20) dependencyOf ms-azuretools.vscode-docker
- target universal (universal); vsix 1.0MB, unpacked 4.3MB, main main.js 0.00MB, js files 5; engines {'vscode': '^1.105.0'}; licence: SEE LICENSE IN LICENSE.md
- activation: ['onTaskType:docker-build', 'onTaskType:docker-run', 'onTaskType:docker-compose', 'onDebugInitialConfigurations', 'onDebugResolve:docker', 'onFileSystem:containers', 'onLanguage:dockerfile', 'onLanguage:dockercompose'] (+115 implicit)
- contributes: menus:130, commands:100, configuration:55, views:7, taskDefinitions:4, languages:2, debuggers:1, configurationDefaults:1, viewsContainers:1, walkthroughs:1, languageModelTools:1; webviewViews 0, whenKeys 13
- top APIs (acorn): l10n.t 582, Uri 65, ThemeIcon 60, workspace.getConfiguration 53, EventEmitter 39, commands.executeCommand 34, window.withProgress 31, ProgressLocation 30, FileType 23, Disposable 19, window.showInformationMessage 18, workspace.fs 18, TreeItemCollapsibleState 17, ConfigurationTarget 16, window.showErrorMessage 15
- native: -; wasm 0; builtins: child_process:6, net:7, https:6, http:3; proposals: -
- blockers: contributes 1 debuggers (debug.dap out of scope); uses chat/lm API (LanguageModelTextPart, LanguageModelToolResult, lm.registerTool); contributes chat/LM/MCP points
### Vue.volar 3.3.11 (10,191,124 dl, rank 25)
- target universal (universal); vsix 1.0MB, unpacked 4.3MB, main ./main.js 0.00MB, js files 13; engines {'vscode': '^1.88.0'}; licence: None
- activation: ['onLanguage'] (+6 implicit)
- contributes: configuration:26, grammars:7, menus:7, languages:4, semanticTokenScopes:3, commands:2, jsonValidation:1, breakpoints:1; webviewViews 0, whenKeys 6
- top APIs (acorn): Uri 26, window.activeTextEditor 15, languages.match 14, commands.executeCommand 13, EventEmitter 12, workspace.getConfiguration 12, DiagnosticSeverity 10, Range 10, CodeActionKind 9, window.showInformationMessage 9, commands.registerCommand 7, window.showErrorMessage 7, workspace.workspaceFolders 7, Disposable 6, Position 6
- native: -; wasm 0; builtins: child_process:4, http:1, https:1, net:2; proposals: -
- blockers: -
### esbenp.prettier-vscode 12.4.0 (9,225,853 dl, rank 27)
- target universal (universal); vsix 3.6MB, unpacked 14.7MB, main ./dist/extension.js 0.11MB, js files 40; engines {'vscode': '^1.101.0'}; licence: MIT
- activation: ['onStartupFinished'] (+7 implicit)
- contributes: configuration:35, languages:5, jsonValidation:3, commands:2; webviewViews 0, whenKeys 1
- top APIs (acorn): workspace.createFileSystemWatcher 12, Uri 7, workspace.getWorkspaceFolder 7, commands.registerCommand 6, LanguageStatusSeverity 6, ThemeColor 6, CodeActionKind 4, commands.executeCommand 4, window.activeTextEditor 4, workspace.getConfiguration 4, workspace.isTrusted 4, workspace.onDidChangeConfiguration 4, WorkspaceEdit 3, CodeAction 2, extensions.getExtension 2
- native: -; wasm 0; builtins: child_process:1, worker_threads:2; proposals: -
- blockers: -
### redhat.vscode-yaml 1.25.2026092308 (7,814,452 dl, rank 32)
- target universal (universal); vsix 1.1MB, unpacked 3.7MB, main ./dist/extension 0.85MB, js files 7; engines {'npm': '>=7.0.0', 'vscode': '^1.63.0'}; licence: MIT
- activation: ['onLanguage:yaml', 'onLanguage:yaml-textmate', 'onLanguage:yaml-tmlanguage', 'onLanguage:ansible', 'onLanguage:azure-pipelines', 'onLanguage:dockercompose', 'onLanguage:github-actions-workflow', 'onLanguage:home-assistant']... (+1 implicit)
- contributes: configuration:30, languages:1, grammars:1, configurationDefaults:1; webviewViews 0, whenKeys 0
- top APIs (acorn): workspace.getConfiguration 38, Uri 30, DiagnosticSeverity 20, window.showErrorMessage 20, CodeActionKind 18, workspace.fs 18, ConfigurationTarget 16, window.showInformationMessage 16, workspace.workspaceFolders 15, FoldingRangeKind 12, languages.match 12, commands.executeCommand 8, CompletionItemTag 8, DiagnosticTag 8, DocumentHighlightKind 8
- native: -; wasm 0; builtins: child_process:2, http:2, https:2, net:2; proposals: -
- blockers: -
### rust-lang.rust-analyzer 0.4.3061 (7,029,060 dl, rank 33)
- target linux-arm64 (alpine-x64, darwin-arm64, darwin-x64, linux-arm64, linux-armhf, linux-x64, universal, win32-arm64, win32-x64); vsix 15.8MB, unpacked 42.8MB, main ./out/main 0.53MB, js files 3; engines {'vscode': '^1.93.0'}; licence: MIT OR Apache-2.0
- activation: ['workspaceContains:Cargo.toml', 'workspaceContains:*/Cargo.toml', 'workspaceContains:rust-project.json', 'workspaceContains:*/rust-project.json', 'workspaceContains:.rust-project.json', 'workspaceContains:*/.rust-project.json'] (+53 implicit)
- contributes: configuration:217, commands:47, semanticTokenTypes:38, menus:35, semanticTokenModifiers:17, problemMatchers:4, problemPatterns:3, keybindings:2, configurationDefaults:2, languages:2, views:2, jsonValidation:2; webviewViews 0, whenKeys 8
- top APIs (acorn): Uri 48, ThemeColor 37, ThemeIcon 24, window.activeTextEditor 22, EventEmitter 19, workspace.workspaceFolders 19, window.showErrorMessage 18, commands.executeCommand 16, workspace.getConfiguration 16, workspace.fs 15, languages.match 14, window.showInformationMessage 14, window.showTextDocument 12, DiagnosticSeverity 10, CodeActionKind 9
- native: 1 (aarch64/glibcx1; 41MB); wasm 0; builtins: net:1, child_process:6; proposals: -
- blockers: ships 1 aarch64 native binaries (0 node addons) - needs native loading in proot
### ms-azuretools.vscode-docker 2.0.0 (6,814,911 dl, rank 34) [declarative]
- target universal (universal); vsix 0.1MB, unpacked 0.1MB, main None 0.00MB, js files 0; engines {'vscode': '^1.92.0'}; licence: SEE LICENSE IN LICENSE.md
- activation: [] (+0 implicit)
- contributes: ; webviewViews 0, whenKeys 0
- top APIs (none): 
- native: -; wasm 0; builtins: ; proposals: -
- blockers: -
### GitHub.vscode-pull-request-github 0.166.1 (6,554,527 dl, rank 36)
- target universal (universal); vsix 1.6MB, unpacked 6.0MB, main ./dist/extension 2.21MB, js files 7; engines {'node': '>=20', 'vscode': '^1.137.0'}; licence: MIT
- activation: ['onStartupFinished', 'onOpenExternalUri:http', 'onOpenExternalUri:https', 'onFileSystem:newIssue', 'onFileSystem:pr', 'onFileSystem:githubpr', 'onFileSystem:githubcommit', 'onFileSystem:review']... (+194 implicit)
- contributes: menus:364, commands:172, configuration:74, viewsWelcome:21, views:11, colors:9, languageModelTools:9, keybindings:6, chatSkills:6, chatContext:2, yamlValidation:2, viewsContainers:2; webviewViews 2, whenKeys 96
- top APIs (acorn): l10n.t 615, window.showErrorMessage 178, commands.registerCommand 172, Uri 156, workspace.getConfiguration 125, EventEmitter 79, commands.executeCommand 64, ThemeIcon 51, window.showWarningMessage 45, window.tabGroups 43, window.showInformationMessage 40, window.activeTextEditor 39, Range 34, workspace.fs 33, MarkdownString 32
- native: -; wasm 0; builtins: https:1, http:1, net:1; proposals: ['activeComment', 'agentSessionsWorkspace', 'agentsWindowActivation', 'chatContextProvider', 'chatParticipantAdditions', 'chatParticipantPrivate', 'chatSessionsProvider', 'codiconDecoration', 'codeActionRanges', 'commentingRangeHint', 'commentReactor', 'commentReveal', 'commentsDraftState', 'commentThreadApplicability', 'contribAccessibilityHelpContent', 'contribCommentEditorActionsMenu', 'contribCommentPeekContext', 'contribCommentThreadAdditionalMenu', 'contribCommentsViewThreadMenus', 'contribEditorContentMenu', 'contribShareMenu', 'diffCommand', 'externalUriOpener', 'languageModelToolResultAudience', 'markdownAlertSyntax', 'quickDiffProvider', 'remoteCodingAgents', 'shareProvider', 'tabInputMultiDiff', 'tokenInformation', 'treeItemMarkdownLabel', 'treeViewMarkdownMessage']
- blockers: proposed API: activeComment, agentSessionsWorkspace, agentsWindowActivation, chatContextProvider, chatParticipantAdditions, chatParticipantPrivate, chatSessionsProvider, codiconDecoration, codeActionRanges, commentingRangeHint, commentReactor, commentReveal, commentsDraftState, commentThreadApplicability, contribAccessibilityHelpContent, contribCommentEditorActionsMenu, contribCommentPeekContext, contribCommentThreadAdditionalMenu, contribCommentsViewThreadMenus, contribEditorContentMenu, contribShareMenu, diffCommand, externalUriOpener, languageModelToolResultAudience, markdownAlertSyntax, quickDiffProvider, remoteCodingAgents, shareProvider, tabInputMultiDiff, tokenInformation, treeItemMarkdownLabel, treeViewMarkdownMessage; uses chat/lm API (LanguageModelTextPart, LanguageModelToolResult, lm.registerTool, LanguageModelChatMessage, chat.registerChatAttachContextProvider, ...); contributes chat/LM/MCP points
### PKief.material-icon-theme 5.38.1 (6,080,100 dl, rank 37)
- target universal (universal); vsix 0.9MB, unpacked 1.9MB, main ./dist/extension/desktop/extension.cjs 0.19MB, js files 2; engines {'vscode': '^1.55.0'}; licence: MIT
- activation: ['onStartupFinished'] (+11 implicit)
- contributes: configuration:17, commands:11, iconThemes:1; webviewViews 0, whenKeys 1
- top APIs (acorn): window.showQuickPick 6, window.showInputBox 4, commands.registerCommand 1, env.language 1, extensions.getExtension 1, window.createOutputChannel 1, window.showInformationMessage 1, workspace.getConfiguration 1, workspace.onDidChangeConfiguration 1
- native: -; wasm 0; builtins: ; proposals: -
- blockers: -
### dbaeumer.vscode-eslint 3.0.34 (5,997,165 dl, rank 38)
- target universal (universal); vsix 0.3MB, unpacked 0.9MB, main ./client/out/extension 0.48MB, js files 3; engines {'vscode': '^1.90.0'}; licence: MIT
- activation: ['onStartupFinished'] (+9 implicit)
- contributes: configuration:40, commands:6, jsonValidation:3, languages:2, taskDefinitions:1; webviewViews 0, whenKeys 0
- top APIs (acorn): Uri 24, EventEmitter 23, workspace.getConfiguration 23, languages.match 18, ConfigurationTarget 16, CodeActionKind 13, commands.registerCommand 12, workspace.workspaceFolders 11, DiagnosticSeverity 10, window.showErrorMessage 10, window.showInformationMessage 10, workspace.textDocuments 9, LanguageStatusSeverity 7, Range 7, window.activeTextEditor 7
- native: -; wasm 0; builtins: child_process:2, net:2; proposals: -
- blockers: -
### Dart-Code.dart-code 3.143.20260901 (5,616,619 dl, rank 39)
- target universal (universal); vsix 16.7MB, unpacked 15.6MB, main ./out/dist/extension 2.97MB, js files 2; engines {'vscode': '^1.101.0'}; licence: SEE LICENSE IN LICENSE
- activation: ['workspaceContains:pubspec.yaml', 'workspaceContains:*/pubspec.yaml', 'workspaceContains:*/*/pubspec.yaml', 'workspaceContains:analysis_options.yaml', 'workspaceContains:*/analysis_options.yaml', 'workspaceContains:*/*/analysis_options.yaml', 'workspaceContains:.dart_tool', 'workspaceContains:*/.dart_tool']... (+127 implicit)
- contributes: menus:167, configuration:137, commands:109, views:11, viewsContainers:6, configurationDefaults:5, keybindings:3, languageModelTools:3, colors:2, taskDefinitions:2, languages:1, grammars:1; webviewViews 10, whenKeys 65
- top APIs (acorn): commands.registerCommand 132, commands.executeCommand 120, Uri 109, window.showErrorMessage 55, EventEmitter 49, window.showInformationMessage 44, workspace.workspaceFolders 36, window.showWarningMessage 33, ConfigurationTarget 29, ProgressLocation 24, window.withProgress 23, workspace.getConfiguration 23, workspace.getWorkspaceFolder 22, Range 19, window.activeTextEditor 19
- native: -; wasm 0; builtins: child_process:2, http:2, https:2, net:2; proposals: -
- blockers: contributes 1 debuggers (debug.dap out of scope); uses chat/lm API (LanguageModelTextPart, LanguageModelToolResult, lm.registerTool, lm.registerMcpServerDefinitionProvider); contributes chat/LM/MCP points
### charliermarsh.ruff 2026.84.0 (3,477,732 dl, rank 49)
- target linux-arm64 (alpine-arm64, alpine-x64, darwin-arm64, darwin-x64, linux-arm64, linux-armhf, linux-x64, win32-arm64, win32-x64); vsix 10.9MB, unpacked 26.6MB, main ./dist/extension.js 0.42MB, js files 2; engines {'vscode': '^1.75.0', 'npm': '>=11.10.0'}; licence: MIT
- activation: ['onLanguage:python', 'onLanguage:markdown', 'workspaceContains:*.py', 'workspaceContains:*.ipynb', 'workspaceContains:*.md', 'workspaceContains:**/pyproject.toml', 'workspaceContains:**/ruff.toml', 'workspaceContains:**/.ruff.toml'] (+7 implicit)
- contributes: configuration:32, commands:7; webviewViews 0, whenKeys 0
- top APIs (acorn): Uri 21, EventEmitter 13, DiagnosticSeverity 10, languages.match 10, workspace.getConfiguration 10, CodeActionKind 9, window.showWarningMessage 9, window.showErrorMessage 8, workspace.workspaceFolders 8, LanguageStatusSeverity 7, l10n.t 6, window.showInformationMessage 6, workspace.textDocuments 6, window.activeTextEditor 5, CancellationError 4
- native: 1 (aarch64/glibcx1; 22MB); wasm 0; builtins: child_process:1, net:1; proposals: -
- blockers: ships 1 aarch64 native binaries (0 node addons) - needs native loading in proot
### bradlc.vscode-tailwindcss 0.16.0 (2,492,674 dl, rank 59)
- target universal (universal); vsix 1.8MB, unpacked 7.1MB, main dist/extension.js 0.40MB, js files 4; engines {'vscode': '^1.67.0'}; licence: MIT
- activation: ['onStartupFinished'] (+3 implicit)
- contributes: configuration:27, grammars:7, commands:2, languages:1; webviewViews 0, whenKeys 4
- top APIs (acorn): Uri 26, workspace.getConfiguration 15, languages.match 13, DiagnosticSeverity 10, window.activeTextEditor 10, CodeActionKind 9, EventEmitter 9, workspace.workspaceFolders 9, Range 8, workspace.textDocuments 8, commands.executeCommand 6, window.showErrorMessage 6, workspace.getWorkspaceFolder 6, Position 5, window.showInformationMessage 5
- native: -; wasm 0; builtins: net:5, child_process:5; proposals: -
- blockers: -
### streetsidesoftware.code-spell-checker 4.9.3 (1,937,197 dl, rank 80)
- target universal (universal); vsix 3.1MB, unpacked 8.1MB, main ./packages/client/dist/extension.cjs 0.96MB, js files 8; engines {'node': '>=22.20.0', 'pnpm': '0', 'yarn': '0', 'vscode': '^1.104.0'}; licence: GPL-3.0-or-later
- activation: ['onStartupFinished'] (+73 implicit)
- contributes: configuration:102, commands:67, menus:28, views:4, configurationDefaults:4, viewsContainers:3, submenus:2, icons:1, languages:1, jsonValidation:1, terminal:1, viewsWelcome:0; webviewViews 1, whenKeys 50
- top APIs (acorn): Uri 65, ConfigurationTarget 40, window.activeTextEditor 39, EventEmitter 32, Range 28, window.showInformationMessage 25, workspace.getConfiguration 19, workspace.fs 18, commands.executeCommand 17, ThemeIcon 15, DiagnosticSeverity 14, commands.registerCommand 13, FileType 13, CodeActionKind 12, MarkdownString 12
- native: -; wasm 0; builtins: child_process:4, net:3, worker_threads:1; proposals: -
- blockers: -
### mhutchie.git-graph 1.30.0 (1,120,180 dl, rank 100)
- target universal (universal); vsix 0.4MB, unpacked 1.0MB, main ./out/extension.js 0.01MB, js files 40; engines {'vscode': '^1.38.0'}; licence: None
- activation: ['*'] (+10 implicit)
- contributes: configuration:110, commands:10, menus:4; webviewViews 0, whenKeys 5
- top APIs (acorn): ViewColumn 14, Uri 11, commands.executeCommand 6, window.activeTextEditor 5, workspace.workspaceFolders 5, window.showQuickPick 4, version 3, window.showInformationMessage 3, workspace.createFileSystemWatcher 3, workspace.getConfiguration 2, commands.registerCommand 1, env.clipboard 1, env.openExternal 1, env.sessionId 1, EventEmitter 1
- native: -; wasm 0; builtins: http:2, https:2, child_process:2; proposals: -
- blockers: -
### usernamehw.errorlens 3.28.0 (1,060,030 dl, rank 102)
- target universal (universal); vsix 0.1MB, unpacked 0.2MB, main ./dist/extension.js 0.07MB, js files 1; engines {'vscode': '^1.107.0', 'npm': '>=10.0.0'}; licence: MIT
- activation: ['onStartupFinished'] (+15 implicit)
- contributes: configuration:76, colors:30, commands:15; webviewViews 0, whenKeys 0
- top APIs (acorn): ThemeColor 32, commands.registerCommand 17, DiagnosticSeverity 16, window.createTextEditorDecorationType 15, languages.getDiagnostics 14, window.activeTextEditor 10, window.visibleTextEditors 9, window.showInformationMessage 8, workspace.getConfiguration 8, commands.executeCommand 7, window.showWarningMessage 7, Range 6, Uri 6, Selection 5, StatusBarAlignment 5
- native: -; wasm 0; builtins: ; proposals: -
- blockers: -
### vscode.yaml 1.95.3 (440,538 dl, rank None) dependencyOf ms-azuretools.vscode-docker [declarative]
- target universal (universal); vsix 0.0MB, unpacked 0.1MB, main None 0.00MB, js files 1; engines {'vscode': '*'}; licence: SEE LICENSE IN LICENSE-vscode.txt
- activation: [] (+2 implicit)
- contributes: languages:2, grammars:2, configurationDefaults:2; webviewViews 0, whenKeys 0
- top APIs (none): 
- native: -; wasm 0; builtins: ; proposals: -
- blockers: -
### vscode.docker 1.95.3 (377,420 dl, rank None) dependencyOf ms-azuretools.vscode-docker [declarative]
- target universal (universal); vsix 0.0MB, unpacked 0.0MB, main None 0.00MB, js files 0; engines {'vscode': '*'}; licence: SEE LICENSE IN LICENSE-vscode.txt
- activation: [] (+1 implicit)
- contributes: languages:1, grammars:1, configurationDefaults:1; webviewViews 0, whenKeys 0
- top APIs (none): 
- native: -; wasm 0; builtins: ; proposals: -
- blockers: -
### vscode.git 1.95.3 (315,692 dl, rank None) dependencyOf Shopify.ruby-lsp
- target universal (universal); vsix 1.5MB, unpacked 5.5MB, main ./dist/main 1.29MB, js files 3; engines {'vscode': '^1.5.0'}; licence: SEE LICENSE IN LICENSE-vscode.txt
- activation: ['*', 'onEditSession:file', 'onFileSystem:git', 'onFileSystem:git-show'] (+140 implicit)
- contributes: menus:319, commands:140, configuration:88, viewsWelcome:19, colors:10, submenus:7, keybindings:3, configurationDefaults:2, continueEditSession:1; webviewViews 0, whenKeys 48
- top APIs (acorn): l10n.t 402, Uri 105, workspace.getConfiguration 84, commands.executeCommand 52, window.showWarningMessage 41, EventEmitter 33, workspace.workspaceFolders 24, window.showInformationMessage 23, workspace.onDidChangeConfiguration 18, window.activeTextEditor 16, ThemeIcon 15, window.showQuickPick 15, Range 14, ThemeColor 11, window.tabGroups 11
- native: -; wasm 0; builtins: http:3, child_process:1, https:1; proposals: ['canonicalUriProvider', 'contribEditSessions', 'contribEditorContentMenu', 'contribMergeEditorMenus', 'contribMultiDiffEditorMenus', 'contribDiffEditorGutterToolBarMenus', 'contribSourceControlHistoryItemMenu', 'contribSourceControlHistoryTitleMenu', 'contribSourceControlInputBoxMenu', 'contribSourceControlTitleMenu', 'contribViewsWelcome', 'diffCommand', 'editSessionIdentityProvider', 'quickDiffProvider', 'quickInputButtonLocation', 'quickPickSortByLabel', 'scmActionButton', 'scmHistoryProvider', 'scmMultiDiffEditor', 'scmSelectedProvider', 'scmTextDocument', 'scmValidation', 'tabInputMultiDiff', 'tabInputTextMerge', 'timeline']
- blockers: proposed API: canonicalUriProvider, contribEditSessions, contribEditorContentMenu, contribMergeEditorMenus, contribMultiDiffEditorMenus, contribDiffEditorGutterToolBarMenus, contribSourceControlHistoryItemMenu, contribSourceControlHistoryTitleMenu, contribSourceControlInputBoxMenu, contribSourceControlTitleMenu, contribViewsWelcome, diffCommand, editSessionIdentityProvider, quickDiffProvider, quickInputButtonLocation, quickPickSortByLabel, scmActionButton, scmHistoryProvider, scmMultiDiffEditor, scmSelectedProvider, scmTextDocument, scmValidation, tabInputMultiDiff, tabInputTextMerge, timeline
### vscode.git-base 1.95.3 (207,588 dl, rank None) dependencyOf Shopify.ruby-lsp
- target universal (universal); vsix 0.0MB, unpacked 0.1MB, main ./dist/extension.js 0.01MB, js files 2; engines {'vscode': '0.10.0'}; licence: SEE LICENSE IN LICENSE-vscode.txt
- activation: ['*'] (+4 implicit)
- contributes: languages:3, grammars:3, commands:1, menus:1; webviewViews 0, whenKeys 0
- top APIs (acorn): l10n.t 11, EventEmitter 3, QuickPickItemKind 2, window.createQuickPick 2, window.showQuickPick 2, commands.registerCommand 1, Disposable 1
- native: -; wasm 0; builtins: ; proposals: -
- blockers: -
### vscode.github-authentication 1.95.3 (133,115 dl, rank None) dependencyOf GitHub.vscode-pull-request-github
- target universal (universal); vsix 1.1MB, unpacked 5.3MB, main ./dist/extension.js 0.52MB, js files 2; engines {'vscode': '^1.41.0'}; licence: SEE LICENSE IN LICENSE-vscode.txt
- activation: [] (+2 implicit)
- contributes: authentication:2, configuration:1; webviewViews 0, whenKeys 0
- top APIs (acorn): l10n.t 19, Uri 13, env.appName 7, env.appHost 5, env.asExternalUri 4, env.openExternal 4, env.isTelemetryEnabled 3, env.uriScheme 3, EventEmitter 3, ProgressLocation 3, window.withProgress 3, workspace.getConfiguration 3, commands.executeCommand 2, window.showErrorMessage 2, window.showInformationMessage 2
- native: -; wasm 0; builtins: http:1, https:1; proposals: -
- blockers: -

## Highest-weight blockers across the corpus (share of 1,091,356,814 downloads; from corpus.json)
| blocker | #ext | weight share | notes |
|---|---|---|---|
| spawns child processes (`child_process` imported) | 126 | 87.7% | LSP servers, CLIs, git: process.spawn is table stakes |
| uses `window.createWebviewPanel` | 59 | 39.5% | plus 38 ext (14.8%) contributing webview views |
| ships native binaries (any format) | 32 | 32.8% | 21 ext (24.9%) include aarch64-glibc ELF (runnable in the guest); 6 (6.1%) have aarch64 `.node` addons |
| contributes debuggers | 22 | 29.5% | debug.dap is out of scope; the flag counts `contributes.debuggers` |
| chat/lm API use or chat/LM/MCP contributions | 31 | 28.6% | chat.lm track |
| enabledApiProposals | 14 | 26.9% | top by weight: terminalDataWriteEvent (9.3%), editorHoverVerbosityLevel (7.9%, pyrefly), portsAttributes (6.7%), quickPickSortByLabel (6.4%), notebookReplDocument/notebookVariableProvider/quickPickItemTooltip (6.4%, ms-python.python), contribEditorContentMenu (6.0%), contribViewsWelcome (5.5%), debugVisualization (5.5%) |
| customEditors | 18 | 14.6% | |
| ELF binaries but none for aarch64-glibc | 5 | 6.6% | ms-python.debugpy (linux-arm64 package still ships 2 x86-64 attach `.so`s), devsense.intelli-php-vscode, GitLab.gitlab-workflow, eclipse-cdt.cdt-gdb-vscode, kombai.kombai |
| wasm files | 26 | 4.7% | |
| notebooks / notebookRenderer | 10 | 3.2% | notebook.api out of scope |
| no glibc linux-arm64 or universal build | 1 | 0.7% | devsense.intelli-php-vscode only (targets darwin-*, linux-x64, win32-*) |
| extension (pack member) not on Open VSX | 1 | - | ms-python.vscode-pylance (extensionPack of ms-python.python; pack members are optional so python still installs) |
| web-only (browser, no main) | 0 | 0% | |
Every glibc aarch64 ELF found needs at most GLIBC_2.38 (muhammad-sammy.csharp, openai.chatgpt); Claude Code's CLI needs GLIBC_2.26.

## engines.vscode distribution (156 ext; lower bound of the range)
<=1.60: 17 | 1.61-1.79: 41 | 1.80-1.89: 21 | 1.90-1.99: 40 | 1.100-1.105: 25 | 1.106-1.114: 6 | 1.137-1.138: 2 | `*`/0.10.0: 4 (vscode.* built-ins).
Highest requirements: k--kato.intellij-idea-keybindings `^1.138.0`, GitHub.vscode-pull-request-github `^1.137.0` (must-work), James-Yu.latex-workshop `^1.114.0`,
ms-python.vscode-python-envs `^1.110.0-20260204`, ms-kubernetes-tools `^1.110.0`, ms-vscode.live-server `^1.109.0`, usernamehw.errorlens `^1.107.0` (must-work).
The emulated `vscode.version` must therefore be reported as >= 1.138 for every must-work extension to install/activate (VS Code main is 1.140.0, per `$VS/package.json`).
engines.node is declared by 20+ extensions, e.g. eamodio.gitlens `>= 24`, hashicorp.terraform `^24.0.0`, redhat.ansible `>=24.13.1`.

## Extractor spot-checks (grep on the unpacked bundle vs extractor call-site count)
| extension / file | API | `grep -o '\.<name>\b'` | extractor | verdict |
|---|---|---|---|---|
| eamodio.gitlens dist/*.js (excluding dist/browser) | window.tabGroups | 33 | 32 (gitlens.js 27, webview-timeline.js 4, webview-rebase.js 1) | ok; 1 textual hit is not an API access. Chunks import vscode via the webpack id 1398 defined in gitlens.js (handled by cross-chunk id sharing) |
| eamodio.gitlens | window.withProgress / createWebviewPanel / registerWebviewViewProvider / registerCustomEditorProvider | 54 / 1 / 1 / 1 | 53 / 1 / 1 / 1 | ok |
| Anthropic.claude-code extension.js | window.tabGroups / createWebviewPanel / registerWebviewViewProvider | 23 / 2 / 3 | 23 / 2 / 3 | exact |
| usernamehw.errorlens dist/extension.js | createTextEditorDecorationType / activeTextEditor / getDiagnostics | 15 / 10 / 15 | 15 / 10 / 14 | ok |
Bugs found and fixed while validating: (1) traversal skipped `Property.value` (webpack factories invisible); (2) `const t = s.window.activeTextEditor` was bound as an API alias so every `t.x` counted (errorlens 46 vs 10); now only first-level aliases are bindings; (3) GitLens double-counted its separate `browser` bundle; web builds in a directory other than `main`'s are now excluded (`apiUsageStats.excludedBrowserBundleFiles`); (4) nested webpack runtimes reuse small ids (rangav.vscode-thunder-client: id 3 is vscode outside, `path` inside); ids are now scoped to the module container. Remaining noise: a few ids like `slice`/`length` from thunder-client (mangled wrapper modules), and `version` counts `vscode.version`. Unfollowable: property-mangled re-export wrappers (thunder-client `O.Ca.showInformationMessage`) are missed.
Rolldown `require(\`vscode\`)`, Closure `const r = require; r('vscode')` inside `__commonJS` factories (googlecloudtools.datacloud) and esbuild `require_vscode()` factories are handled.

## Brief fact checks
| claim | verdict | evidence |
|---|---|---|
| Claude Code version | latest 2.1.282, published 2026-09-24T19:03Z | `https://open-vsx.org/api/Anthropic/claude-code` |
| Claude Code "default target alpine-arm64" | CORRECT but misleading: `/api/Anthropic/claude-code` with no target returns the alpine-arm64 record (`"targetPlatform":"alpine-arm64"`). meta.pyrefly also returns alpine-arm64 and ms-python.debugpy returns darwin-arm64, so it looks like the first target alphabetically, not a publisher default | cached API JSON |
| Claude Code linux-arm64 exists? | YES for 2.1.282. Targets: alpine-arm64, alpine-x64, darwin-arm64, darwin-x64, linux-arm64, linux-x64, win32-arm64, win32-x64 (no linux-armhf, no universal, no web) | `downloads` map |
| Claude Code ~230 MB native binary | CORRECT (approx.): `resources/native-binary/claude` is 238,084,088 B (227 MiB), ELF aarch64, interp `/lib/ld-linux-aarch64.so.1` (glibc), needs GLIBC_2.26, NEEDED librt/libc/libpthread/libdl/libm. Also ships `resources/audio-capture/arm64-linux/audio-capture.node` (aarch64 glibc addon) | `file`, `readelf -d` |
| Claude Code sizes | vsix 111,475,614 B; unpacked 249,104,236 B (29 files); extension.js 3,069,046 B; webview bundle `webview/index.js` 5,378,584 B + `index.css` 429,126 B | unzip -l, ls |
| Claude Code licence "all rights reserved" | CORRECT. Open VSX `license` (and package.json `license`) read exactly: "© Anthropic PBC. All rights reserved. Use is subject to the Legal Agreements outlined here: https://code.claude.com/docs/en/legal-and-compliance." There is NO LICENSE file in the vsix (files: README.md, package.json, extension.js, claude-code-settings.schema.json, resources/, webview/) and the registry offers no `files.license`, so no licence text can be quoted | package.json, vsix listing |
| Claude Code profile | engines `^1.94.0`; activation onStartupFinished + onWebviewPanel:claudeVSCodePanel; 31 commands; 3 webview views in containers activitybar(2) + secondarySidebar(1); no proposals; extensionKind [workspace] | corpus.json |
| GitLens engines ^1.101 | CORRECT: `^1.101.0` (plus node `>= 24`, pnpm `>= 11.0.0`); version 2026.9.250515 | package.json |
| GitLens 1165 commands | CORRECT (1165; commandPalette menu has 1151 entries) | package.json |
| GitLens customEditors | CORRECT: 1 | |
| GitLens mcpServerDefinitionProviders | CORRECT: 1 (`gitlens.gkMcpProvider`) | |
| GitLens 61 viewsWelcome / 57 submenus | CORRECT: 61 / 57 | |
| GitLens tabGroups call sites | 32 extractor call sites (33 textual) in the node bundles; the brief's "heavy" usage holds (rank 19 of 155 distinct GitLens API ids) | spot-check above |
| GitLens other | no enabledApiProposals; 5 webview views; menus: view/item/context 875, view/title 439, webview/context 323; activation incl. onFileSystem:gitlens, onTerminal:*, 4x onWebviewPanel | corpus.json |

## Other observations
- Registry licence strings are not always resolved: eamodio.gitlens reports `license: "%gitlens.license%"` (an unresolved NLS placeholder) on `https://open-vsx.org/api/eamodio/gitlens`; the vsix ships LICENSE.txt. Licence data for policy work should come from the vsix, not only the registry field.
- `contributes.configuration` in corpus.json counts settings (properties), e.g. GitLens 466 settings in 44 sections.
- Implicit activation events (VS Code >= 1.74) dominate: GitLens declares 8 explicit events but gets 1189 implicit ones from 1165 commands, 21 views, 1 custom editor, 1 MCP provider, walkthroughs.
- Fork-specific API namespaces seen in bundles: `vscode.antigravityAuth` / `antigravityExtensibility` (jlcodes.antigravity-cockpit, googlecloudtools.datacloud), `vscode.cursor` (highagency.pencildev, nrwl.angular-console). UNVERIFIED whether they feature-detect these (check with grep of the bundles for a guard such as `vscode.cursor?`).
- Proposal runtime checks were not analysed (skipped, per the brief).
