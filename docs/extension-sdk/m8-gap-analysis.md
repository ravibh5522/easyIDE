# Extension SDK - M8 gap analysis: is a Node extension host (L3) worth building?

Status: DECISION INPUT (2026-09-24). Answers the M8 entry condition in [arch.md](arch.md) sec 12:
"only started if a written gap analysis shows L1+L2 cannot serve named, requested extensions".

## Method

easyIDE has no users requesting extensions yet, so "requested" is approximated by demand
elsewhere: the 60 most-downloaded extensions on Open VSX (the registry easyIDE can legally use,
arch.md sec 2.1), queried 2026-09-24 (`/api/-/search?sortBy=downloadCount`). Each is classified by
the **lowest layer that could deliver its core value** on easyIDE:

| Class | Meaning |
|---|---|
| **Served** | Shipped today or expressible now as an L0/L1 pack (grammar, theme, snippets, language server via `easyide.languageServers`, commands/actions, tasks) |
| **L2** | Needs custom logic that the WASM layer (host API: editor, fs, ui, providers, sandbox, net) can express |
| **L3** | Needs the Node extension host specifically: JS logic against the `vscode` API that L1/L2 cannot express |
| **Out of scope** | Blocked by something L3 does not provide: debugging (DAP), notebooks, webviews/rich UI, a remote service or daemon unavailable in the sandbox |

## Classification (top 60, grouped)

| Extensions (downloads, millions) | What users get from it | Class | Why |
|---|---|---|---|
| meta.pyrefly (86), ms-python.python (59), Codeium.windsurfPyright (2.8), charliermarsh.ruff (3.4) | Python analysis, formatting | **Served** | `easyide.python` (pyright + ruff); pyrefly is one more `languageServers` entry |
| golang.Go (41), llvm-vs-code-extensions.vscode-clangd (30), redhat.vscode-yaml (7.8), rust-lang.rust-analyzer (7.0), Shopify.ruby-lsp (43), Vue.volar (10), Angular.ng-template (2.8), bradlc.vscode-tailwindcss (2.5), Dart-Code.dart-code (5.6, language part) | language intelligence | **Served** | the M4 packs (go, cpp, yaml, rust) or the same pack shape with another server; Ruby, Vue, Angular, Tailwind and Dart are one pack each, no new platform work |
| redhat.java (43), vscjava.vscode-java-pack (22), vscjava.vscode-java-dependency (40) | Java intelligence and project view | **Served** / **L2** | jdtls as a language server (memory budget ~1 GB: tight on a tablet); the dependency tree is `easyide.viewData` or L2 `ui.setViewData` |
| vscjava.vscode-maven (28), vscjava.vscode-gradle (27), devsense.composer-php-vscode (41) | run build goals, project trees | **Served** / **L2** | tasks and `runInTerminal` commands (the `easyide.project-tasks` pattern); trees via view data |
| devsense.phptools-vscode (40), devsense.intelli-php-vscode (7.8) | PHP intelligence | **Served** | an LSP pack (phpactor or intelephense); the proprietary Devsense server is a licensing question, not a platform gap |
| esbenp.prettier-vscode (9.2), dbaeumer.vscode-eslint (6.0) | format and lint JS/TS | **Served** / **gap in L1** | eslint ships a language server (`vscode-eslint-language-server`, in `easyide.web`'s package); prettier is a CLI. L1 has no "format this document with a command" contribution, so today it is a Run command, not format-on-save. **Gap 1** |
| magicstack.MagicPython (12), EricSia.pythonsnippets3 (12), PKief.material-icon-theme (6.1), mechatroner.rainbow-csv (3.2, colouring) | grammars, snippets, icon theme | **Served** | L0 contribution points; icon themes land with M4 |
| mechatroner.rainbow-csv (SQL-like queries) | query CSV | **L2** | pure computation over the document: `editor.getText` + `ui.setViewData` |
| MS-CEINTL.vscode-language-pack-zh-hans (3.6) | UI in Chinese | **gap** | a localisation pack changes the host UI strings; easyIDE has no contribution point for that. Neither L2 nor L3 helps. **Gap 2** |
| eamodio.gitlens (17) | blame, history, graph | **Served** in part / **Out of scope** in part | blame, file history and the graph are the git-extras pack and the planned native git UI; the rich GitLens panels are webviews |
| GitHub.vscode-pull-request-github (6.6), GitLab.gitlab-workflow (2.6), GitHub.vscode-github-actions (4.4) | PR and CI review | **Out of scope** | webviews plus OAuth sign-in flows; L3 alone cannot render them |
| ms-python.debugpy (60), vscjava.vscode-java-debug (26), ms-vscode.js-debug (3.1), devsense.profiler-php-vscode (41), Dart-Code.flutter (4.8, debug) | debugging, profiling | **Out of scope** | DAP and a debug UI are not planned (arch.md sec 1); an extension host without DAP does not help |
| vscjava.vscode-java-test (26) | test explorer | **gap** | no test-explorer surface in easyIDE. L2 view data covers a list, not run/debug state. **Gap 3** |
| ms-toolsai.jupyter (10) and its renderers, keymap, cell-tags and slideshow companions (8-9.7 each), quarto.quarto (4.3), posit.shiny (2.9) | notebooks, publishing | **Out of scope** | notebooks are not planned (0009) |
| ms-azuretools.vscode-containers (14), ms-azuretools.vscode-docker (6.8) | containers | **Out of scope** | no container daemon inside a proot environment |
| Anthropic.claude-code (52), openai.chatgpt (12), saoudrizwan.claude-dev (6.7), kilocode.kilo-code (4.2), Google.geminicodeassist (4.0) | AI agents | **Out of scope** as extensions | webview chat UIs plus Node. Claude Code runs as its CLI in a terminal (the ux-overhaul plan), which is the better fit for a tablet |
| rangav.vscode-thunder-client (2.7), ritwickdey.LiveServer (3.0), amazonwebservices.aws-toolkit-vscode (3.5), googlecloudtools.datacloud (3.0), anyscalecompute.anyscale-workspaces (3.9), jlcodes.antigravity-cockpit (5.0) | API client, live preview, cloud consoles | **Out of scope** | webviews or cloud sign-in UIs; Live Server's core (serve a folder) is a task plus the browser |
| ms-python.vscode-python-envs (42) | pick an environment | **Served** | `python.selectVenv` in `easyide.python` |
| ms-vscode.powershell (2.4) | PowerShell | **Served** | an LSP pack (PowerShellEditorServices needs `pwsh` in the environment) |

## Findings

1. **No extension in the top 60 needs the Node host itself.** Every extension whose value is
   language intelligence, snippets, themes or build commands is served by L0/L1 packs today or by one
   more pack of the same shape. What blocks the rest is **DAP, notebooks, webviews or sign-in UIs,
   and container daemons**. An L3 host would not unblock any of those; they need an L4 webview
   host or Theia, a debug UI, or features the device cannot run.
2. Three concrete gaps sit in L1, and one is outside the extension layers:
   - **Gap 1: formatter contribution.** Format a document or selection through a sandbox command
     (prettier, black, gofmt) and join it to `editor.formatOnSave`, like VS Code's
     `documentFormattingEditProvider` but declarative (`{"command": ["prettier", "--stdin-filepath", "${file}"]}`
     reading stdin and writing stdout).
   - **Gap 3: test explorer.** A view contribution for test items with run state, fed by view data
     or L2.
   - **Gap 2: localisation packs.** App UI string packs; this is app i18n, not an extension-layer
     question.
   - The Open VSX subset importer (registry-and-install.md sec 9) would also let the 13
     "Served"-by-contribution entries (grammars, snippets, themes, icon themes) install directly.
3. The cost stays as arch.md estimates. L3 means Node in every environment, an RPC bridge for a
   `vscode` API subset, a memory budget per host, and the credential-helper exposure called out
   in 0009. That is the largest item on the roadmap, for no extension in this sample.

## Recommendation

**Do not build M8 now.** Close Gaps 1 and 3 in L1 (small, declarative, testable with `easyide-ext test`),
finish the Open VSX subset import, and re-run this analysis when easyIDE has real extension requests.
Log every request that L1/L2 cannot serve, including requests arriving through the registry index
repo. Build L3 only if one of those names an extension whose value is JS logic against the
`vscode` API with no webview, debugger or notebook.
