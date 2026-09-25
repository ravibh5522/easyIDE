# Must-work extension profiles (generated)

Part of [corpus.md](corpus.md) (`## 5`). Generated from [data/corpus.json](data/corpus.json) by
`tools/vsx-audit/render-corpus.mjs`; regenerate per [corpus.md `## 7`](corpus.md#7-how-to-regenerate). One block per
must-work id (D7's 22-id list), in the order `corpus.json` `meta.mustWorkList` gives them. `spike status` cites
[research/route-spike.md](research/route-spike.md) §3 — only 8 real extensions were run in the route spike, so most
ids here read "not run in the spike", which is a scope note, not a failure.

<!-- gen:start must_work_profiles -->
### eamodio.gitlens 2026.9.250515 (16,867,728 dl, rank 19)
- licence: %gitlens.license% | target: universal | engines.vscode: ^1.101.0
- activation: ["onFileSystem:gitlens","onStartupFinished","onTerminal:*","onUri","onWebviewPanel:gitlens.graph","onWebviewPanel:gitlens.patchDetails","onWebviewPanel:gitlens.settings","onWebviewPanel:gitlens.timeline"] (+1189 implicit)
- contributes: menus:3450, commands:1165, configuration:466, icons:94, colors:76, viewsWelcome:61, keybindings:59, submenus:57, views:21, viewsContainers:4, configurationDefaults:2, resourceLabelFormatters:2, mcpServerDefinitionProviders:1, customEditors:1, walkthroughs:1
- top 10 APIs by call sites: l10n.t(3677), Uri(279), ThemeIcon(187), window.showWarningMessage(168), window.showErrorMessage(133), TreeItemCollapsibleState(97), Disposable(92), window.showInformationMessage(86), EventEmitter(75), workspace.fs(69)
- native binaries: 0 | proposals: -
- OOS parts: CHAT (chat/lm API or contributions)
- blockers: uses chat/lm API (LanguageModelChatMessage, LanguageModelTextPart, LanguageModelToolCallPart, lm.registerMcpServerDefinitionProvider, lm.selectChatModels, ...); contributes chat/LM/MCP points
- spike status: activated unmodified in the route-spike (research/route-spike.md §3)

### Anthropic.claude-code 2.1.282 (52,395,010 dl, rank 4)
- licence: © Anthropic PBC. All rights reserved. Use is subject to the Legal Agreements outlined here: https://code.claude.com/docs/en/legal-and-compliance. | target: linux-arm64 | engines.vscode: ^1.94.0
- activation: ["onStartupFinished","onWebviewPanel:claudeVSCodePanel"] (+35 implicit)
- contributes: commands:31, menus:28, configuration:20, keybindings:9, viewsContainers:3, views:3, jsonValidation:1, walkthroughs:1
- top 10 APIs by call sites: commands.executeCommand(52), Uri(43), commands.registerCommand(32), window.tabGroups(23), workspace.getConfiguration(23), workspace.workspaceFolders(22), window.showInformationMessage(18), window.showErrorMessage(12), workspace.openTextDocument(12), Range(11)
- native binaries: 2 (2 (Y)) | proposals: -
- OOS parts: none
- blockers: ships 2 aarch64 native binaries (1 node addons) - needs native loading in proot
- spike status: activated unmodified in the route-spike (research/route-spike.md §3)

### esbenp.prettier-vscode 12.4.0 (9,225,853 dl, rank 27)
- licence: MIT | target: universal | engines.vscode: ^1.101.0
- activation: ["onStartupFinished"] (+7 implicit)
- contributes: configuration:35, languages:5, jsonValidation:3, commands:2
- top 10 APIs by call sites: workspace.createFileSystemWatcher(12), Uri(7), workspace.getWorkspaceFolder(7), commands.registerCommand(6), LanguageStatusSeverity(6), ThemeColor(6), CodeActionKind(4), commands.executeCommand(4), window.activeTextEditor(4), workspace.getConfiguration(4)
- native binaries: 0 | proposals: -
- OOS parts: none
- blockers: -
- spike status: activated unmodified in the route-spike (research/route-spike.md §3)

### dbaeumer.vscode-eslint 3.0.34 (5,997,165 dl, rank 38)
- licence: MIT | target: universal | engines.vscode: ^1.90.0
- activation: ["onStartupFinished"] (+9 implicit)
- contributes: configuration:40, commands:6, jsonValidation:3, languages:2, taskDefinitions:1
- top 10 APIs by call sites: Uri(24), EventEmitter(23), workspace.getConfiguration(23), languages.match(18), ConfigurationTarget(16), CodeActionKind(13), commands.registerCommand(12), workspace.workspaceFolders(11), DiagnosticSeverity(10), window.showErrorMessage(10)
- native binaries: 0 | proposals: -
- OOS parts: none
- blockers: -
- spike status: activated unmodified in the route-spike (research/route-spike.md §3)

### redhat.vscode-yaml 1.25.2026092308 (7,814,452 dl, rank 32)
- licence: MIT | target: universal | engines.vscode: ^1.63.0
- activation: ["onLanguage:yaml","onLanguage:yaml-textmate","onLanguage:yaml-tmlanguage","onLanguage:ansible","onLanguage:azure-pipelines","onLanguage:dockercompose","onLanguage:github-actions-workflow","onLanguage:home-assistant","onLanguage:manifest-yaml","onLanguage:spring-boot-properties-yaml"] (+1 implicit)
- contributes: configuration:30, languages:1, grammars:1, configurationDefaults:1
- top 10 APIs by call sites: workspace.getConfiguration(38), Uri(30), DiagnosticSeverity(20), window.showErrorMessage(20), CodeActionKind(18), workspace.fs(18), ConfigurationTarget(16), window.showInformationMessage(16), workspace.workspaceFolders(15), FoldingRangeKind(12)
- native binaries: 0 | proposals: -
- OOS parts: none
- blockers: -
- spike status: not run in the spike (only 8 extensions were spiked); expected to load per D1 evidence, UNVERIFIED for this id

### redhat.java 1.57.2026092508 (42,743,388 dl, rank 6)
- licence: EPL-2.0 | target: linux-arm64 | engines.vscode: ^1.77.0
- activation: ["workspaceContains:pom.xml","workspaceContains:*/pom.xml","workspaceContains:build.gradle","workspaceContains:*/build.gradle","workspaceContains:settings.gradle","workspaceContains:*/settings.gradle","workspaceContains:build.gradle.kts","workspaceContains:*/build.gradle.kts","workspaceContains:settings.gradle.kts","workspaceContains:*/settings.gradle.kts","workspaceContains:.classpath","workspaceContains:*/.classpath","onCommand:_java.templateVariables","onCommand:_java.metadataFilesGeneration"] (+40 implicit)
- contributes: configuration:137, commands:36, menus:35, semanticTokenModifiers:8, grammars:8, keybindings:6, semanticTokenTypes:5, javaShortcuts:4, languages:3, javaBuildFilePatterns:2, javaBuildTools:2, customEditors:1, semanticTokenScopes:1, jsonValidation:1, configurationDefaults:1
- top 10 APIs by call sites: Uri(103), commands.executeCommand(82), commands.registerCommand(73), workspace.getConfiguration(58), window.activeTextEditor(47), EventEmitter(33), CodeActionKind(31), window.showErrorMessage(29), workspace.workspaceFolders(29), window.showInformationMessage(24)
- native binaries: 66 (66 (Y)) | proposals: -
- OOS parts: none
- blockers: ships 66 aarch64 native binaries (0 node addons) - needs native loading in proot
- spike status: not run in the spike (only 8 extensions were spiked); expected to load per D1 evidence, UNVERIFIED for this id

### golang.Go 0.56.1 (40,903,961 dl, rank 8)
- licence: MIT | target: universal | engines.vscode: ^1.90.0
- activation: ["onLanguage:go","onLanguage:go.sum","onLanguage:gotmpl","onLanguage:go.asm","onDebugInitialConfigurations","onDebugResolve:go","onWebviewPanel:welcomeGo"] (+77 implicit)
- contributes: commands:67, configuration:67, menus:39, languages:6, grammars:3, views:3, snippets:1, configurationDefaults:1, breakpoints:1, debuggers:1, taskDefinitions:1
- top 10 APIs by call sites: window.showInformationMessage(70), window.showErrorMessage(69), window.activeTextEditor(64), Uri(54), commands.executeCommand(43), ThemeIcon(28), workspace.workspaceFolders(27), window.showWarningMessage(23), EventEmitter(19), Range(18)
- native binaries: 0 | proposals: -
- OOS parts: DAP (contributes.debuggers)
- blockers: contributes 1 debuggers (debug.dap out of scope)
- spike status: activated unmodified in the route-spike (research/route-spike.md §3)

### rust-lang.rust-analyzer 0.4.3061 (7,029,060 dl, rank 33)
- licence: MIT OR Apache-2.0 | target: linux-arm64 | engines.vscode: ^1.93.0
- activation: ["workspaceContains:Cargo.toml","workspaceContains:*/Cargo.toml","workspaceContains:rust-project.json","workspaceContains:*/rust-project.json","workspaceContains:.rust-project.json","workspaceContains:*/.rust-project.json"] (+53 implicit)
- contributes: configuration:217, commands:47, semanticTokenTypes:38, menus:35, semanticTokenModifiers:17, problemMatchers:4, problemPatterns:3, keybindings:2, configurationDefaults:2, languages:2, views:2, jsonValidation:2, taskDefinitions:1, grammars:1, semanticTokenScopes:1, viewsWelcome:1, viewsContainers:1, walkthroughs:1
- top 10 APIs by call sites: Uri(48), ThemeColor(37), ThemeIcon(24), window.activeTextEditor(22), EventEmitter(19), workspace.workspaceFolders(19), window.showErrorMessage(18), commands.executeCommand(16), workspace.getConfiguration(16), workspace.fs(15)
- native binaries: 1 (1 (Y)) | proposals: -
- OOS parts: none
- blockers: ships 1 aarch64 native binaries (0 node addons) - needs native loading in proot
- spike status: not run in the spike (only 8 extensions were spiked); expected to load per D1 evidence, UNVERIFIED for this id

### ms-python.python 2026.4.0 (59,121,980 dl, rank 3)
- licence: MIT | target: universal | engines.vscode: ^1.95.0
- activation: ["onDebugInitialConfigurations","onLanguage:python","onDebugResolve:python","onCommand:python.copilotSetupTests","workspaceContains:mspythonconfig.json","workspaceContains:pyproject.toml","workspaceContains:Pipfile","workspaceContains:setup.py","workspaceContains:requirements.txt","workspaceContains:pylock.toml","workspaceContains:**/pylock.*.toml","workspaceContains:manage.py","workspaceContains:app.py","workspaceContains:.venv","workspaceContains:.conda","onLanguageModelTool:get_python_environment_details","onLanguageModelTool:get_python_executable_details","onLanguageModelTool:install_python_packages","onLanguageModelTool:configure_python_environment","onLanguageModelTool:create_virtual_environment","onTerminalShellIntegration:python"] (+37 implicit)
- contributes: configuration:41, menus:38, commands:23, languages:6, languageModelTools:6, breakpoints:5, keybindings:4, jsonValidation:3, yamlValidation:3, walkthroughs:2, submenus:2, problemMatchers:1, debuggers:1, grammars:1, viewsWelcome:1
- top 10 APIs by call sites: l10n.t(314), Uri(93), EventEmitter(64), workspace.workspaceFolders(52), ConfigurationTarget(46), DiagnosticSeverity(27), Range(16), languages.match(14), Position(14), CancellationError(13)
- native binaries: 0 | proposals: contribEditorContentMenu, quickPickSortByLabel, testObserver, quickPickItemTooltip, terminalDataWriteEvent, terminalExecuteCommandEvent, codeActionAI, notebookReplDocument, notebookVariableProvider
- OOS parts: DAP (contributes.debuggers); CHAT (chat/lm API or contributions)
- blockers: proposed API: contribEditorContentMenu, quickPickSortByLabel, testObserver, quickPickItemTooltip, terminalDataWriteEvent, terminalExecuteCommandEvent, codeActionAI, notebookReplDocument, notebookVariableProvider; contributes 1 debuggers (debug.dap out of scope); uses chat/lm API (lm.registerTool, LanguageModelTextPart, LanguageModelToolResult, lm.invokeTool); contributes chat/LM/MCP points
- spike status: not run in the spike (only 8 extensions were spiked); expected to load per D1 evidence, UNVERIFIED for this id

### charliermarsh.ruff 2026.84.0 (3,477,732 dl, rank 49)
- licence: MIT | target: linux-arm64 | engines.vscode: ^1.75.0
- activation: ["onLanguage:python","onLanguage:markdown","workspaceContains:*.py","workspaceContains:*.ipynb","workspaceContains:*.md","workspaceContains:**/pyproject.toml","workspaceContains:**/ruff.toml","workspaceContains:**/.ruff.toml"] (+7 implicit)
- contributes: configuration:32, commands:7
- top 10 APIs by call sites: Uri(21), EventEmitter(13), DiagnosticSeverity(10), languages.match(10), workspace.getConfiguration(10), CodeActionKind(9), window.showWarningMessage(9), window.showErrorMessage(8), workspace.workspaceFolders(8), LanguageStatusSeverity(7)
- native binaries: 1 (1 (Y)) | proposals: -
- OOS parts: none
- blockers: ships 1 aarch64 native binaries (0 node addons) - needs native loading in proot
- spike status: not run in the spike (only 8 extensions were spiked); expected to load per D1 evidence, UNVERIFIED for this id

### meta.pyrefly 1.3.9001 (86,563,040 dl, rank 1)
- licence: MIT | target: linux-arm64 | engines.vscode: ^1.103.0
- activation: ["onLanguage:python","onNotebook:jupyter-notebook"] (+7 implicit)
- contributes: configuration:22, commands:6, configurationDefaults:4, languages:1, semanticTokenScopes:1
- top 10 APIs by call sites: Uri(21), languages.match(13), workspace.getConfiguration(13), EventEmitter(11), DiagnosticSeverity(10), MarkdownString(10), window.showErrorMessage(10), CodeActionKind(9), commands.registerCommand(7), extensions.getExtension(7)
- native binaries: 1 (1 (Y)) | proposals: editorHoverVerbosityLevel
- OOS parts: none
- blockers: ships 1 aarch64 native binaries (0 node addons) - needs native loading in proot; proposed API: editorHoverVerbosityLevel
- spike status: not run in the spike (only 8 extensions were spiked); expected to load per D1 evidence, UNVERIFIED for this id

### llvm-vs-code-extensions.vscode-clangd 0.6.0 (30,200,038 dl, rank 13)
- licence: MIT | target: universal | engines.vscode: ^1.75.0
- activation: ["onLanguage:c","onLanguage:cpp","onLanguage:cuda-cpp","onLanguage:objective-c","onLanguage:objective-cpp"] (+21 implicit)
- contributes: configuration:17, commands:17, menus:12, views:3, keybindings:2, languages:1, colors:1
- top 10 APIs by call sites: Uri(25), commands.registerCommand(19), workspace.getConfiguration(18), commands.executeCommand(17), window.showInformationMessage(17), EventEmitter(14), languages.match(14), DiagnosticSeverity(10), CodeActionKind(9), window.activeTextEditor(9)
- native binaries: 0 | proposals: -
- OOS parts: none
- blockers: -
- spike status: not run in the spike (only 8 extensions were spiked); expected to load per D1 evidence, UNVERIFIED for this id

### Vue.volar 3.3.11 (10,191,124 dl, rank 25)
- licence: ? | target: universal | engines.vscode: ^1.88.0
- activation: ["onLanguage"] (+6 implicit)
- contributes: configuration:26, grammars:7, menus:7, languages:4, semanticTokenScopes:3, commands:2, jsonValidation:1, breakpoints:1
- top 10 APIs by call sites: Uri(26), window.activeTextEditor(15), languages.match(14), commands.executeCommand(13), EventEmitter(12), workspace.getConfiguration(12), DiagnosticSeverity(10), Range(10), CodeActionKind(9), window.showInformationMessage(9)
- native binaries: 0 | proposals: -
- OOS parts: none
- blockers: -
- spike status: not run in the spike (only 8 extensions were spiked); expected to load per D1 evidence, UNVERIFIED for this id

### bradlc.vscode-tailwindcss 0.16.0 (2,492,674 dl, rank 59)
- licence: MIT | target: universal | engines.vscode: ^1.67.0
- activation: ["onStartupFinished"] (+3 implicit)
- contributes: configuration:27, grammars:7, commands:2, languages:1
- top 10 APIs by call sites: Uri(26), workspace.getConfiguration(15), languages.match(13), DiagnosticSeverity(10), window.activeTextEditor(10), CodeActionKind(9), EventEmitter(9), workspace.workspaceFolders(9), Range(8), workspace.textDocuments(8)
- native binaries: 0 | proposals: -
- OOS parts: none
- blockers: -
- spike status: not run in the spike (only 8 extensions were spiked); expected to load per D1 evidence, UNVERIFIED for this id

### GitHub.vscode-pull-request-github 0.166.1 (6,554,527 dl, rank 36)
- licence: MIT | target: universal | engines.vscode: ^1.137.0
- activation: ["onStartupFinished","onOpenExternalUri:http","onOpenExternalUri:https","onFileSystem:newIssue","onFileSystem:pr","onFileSystem:githubpr","onFileSystem:githubcommit","onFileSystem:review","onWebviewPanel:IssueOverview","onWebviewPanel:PullRequestOverview","onChatContextProvider:githubpr","onChatContextProvider:githubissue"] (+194 implicit)
- contributes: menus:364, commands:172, configuration:74, viewsWelcome:21, views:11, colors:9, languageModelTools:9, keybindings:6, chatSkills:6, chatContext:2, yamlValidation:2, viewsContainers:2, resourceLabelFormatters:1
- top 10 APIs by call sites: l10n.t(615), window.showErrorMessage(178), commands.registerCommand(172), Uri(156), workspace.getConfiguration(125), EventEmitter(79), commands.executeCommand(64), ThemeIcon(51), window.showWarningMessage(45), window.tabGroups(43)
- native binaries: 0 | proposals: activeComment, agentSessionsWorkspace, agentsWindowActivation, chatContextProvider, chatParticipantAdditions, chatParticipantPrivate, chatSessionsProvider, codiconDecoration, codeActionRanges, commentingRangeHint, commentReactor, commentReveal, commentsDraftState, commentThreadApplicability, contribAccessibilityHelpContent, contribCommentEditorActionsMenu, contribCommentPeekContext, contribCommentThreadAdditionalMenu, contribCommentsViewThreadMenus, contribEditorContentMenu, contribShareMenu, diffCommand, externalUriOpener, languageModelToolResultAudience, markdownAlertSyntax, quickDiffProvider, remoteCodingAgents, shareProvider, tabInputMultiDiff, tokenInformation, treeItemMarkdownLabel, treeViewMarkdownMessage
- OOS parts: CHAT (chat/lm API or contributions)
- blockers: proposed API: activeComment, agentSessionsWorkspace, agentsWindowActivation, chatContextProvider, chatParticipantAdditions, chatParticipantPrivate, chatSessionsProvider, codiconDecoration, codeActionRanges, commentingRangeHint, commentReactor, commentReveal, commentsDraftState, commentThreadApplicability, contribAccessibilityHelpContent, contribCommentEditorActionsMenu, contribCommentPeekContext, contribCommentThreadAdditionalMenu, contribCommentsViewThreadMenus, contribEditorContentMenu, contribShareMenu, diffCommand, externalUriOpener, languageModelToolResultAudience, markdownAlertSyntax, quickDiffProvider, remoteCodingAgents, shareProvider, tabInputMultiDiff, tokenInformation, treeItemMarkdownLabel, treeViewMarkdownMessage; uses chat/lm API (LanguageModelTextPart, LanguageModelToolResult, lm.registerTool, LanguageModelChatMessage, chat.registerChatAttachContextProvider, ...); contributes chat/LM/MCP points
- spike status: not run in the spike (only 8 extensions were spiked); expected to load per D1 evidence, UNVERIFIED for this id

### ms-azuretools.vscode-docker 2.0.0 (6,814,911 dl, rank 34)
- licence: SEE LICENSE IN LICENSE.md | target: universal | engines.vscode: ^1.92.0
- activation: [] (+0 implicit)
- contributes: (none)
- top 10 APIs by call sites: (none)
- native binaries: 0 | proposals: -
- OOS parts: none
- blockers: -
- spike status: not run in the spike (only 8 extensions were spiked); expected to load per D1 evidence, UNVERIFIED for this id

### Shopify.ruby-lsp 0.10.6 (43,132,270 dl, rank 5)
- licence: MIT | target: universal | engines.vscode: ^1.91.0
- activation: ["workspaceContains:Gemfile.lock","workspaceContains:gems.locked"] (+27 implicit)
- contributes: commands:22, configuration:17, menus:6, grammars:3, languages:3, configurationDefaults:3, chatParticipants:1, views:1, breakpoints:1, debuggers:1, snippets:1
- top 10 APIs by call sites: Uri(137), workspace.getConfiguration(40), workspace.fs(35), commands.registerCommand(30), commands.executeCommand(25), EventEmitter(25), window.showErrorMessage(15), window.showInformationMessage(15), languages.match(14), window.activeTextEditor(13)
- native binaries: 0 | proposals: -
- OOS parts: DAP (contributes.debuggers); CHAT (chat/lm API or contributions)
- blockers: contributes 1 debuggers (debug.dap out of scope); uses chat/lm API (LanguageModelChatMessage, chat.createChatParticipant, ChatRequestTurn, ChatResponseMarkdownPart, ChatResponseTurn, ...); contributes chat/LM/MCP points
- spike status: not run in the spike (only 8 extensions were spiked); expected to load per D1 evidence, UNVERIFIED for this id

### Dart-Code.dart-code 3.143.20260901 (5,616,619 dl, rank 39)
- licence: SEE LICENSE IN LICENSE | target: universal | engines.vscode: ^1.101.0
- activation: ["workspaceContains:pubspec.yaml","workspaceContains:*/pubspec.yaml","workspaceContains:*/*/pubspec.yaml","workspaceContains:analysis_options.yaml","workspaceContains:*/analysis_options.yaml","workspaceContains:*/*/analysis_options.yaml","workspaceContains:.dart_tool","workspaceContains:*/.dart_tool","workspaceContains:*/*/.dart_tool","workspaceContains:*.dart","workspaceContains:*/*.dart","workspaceContains:*/*/*.dart","workspaceContains:dart.sh.create","workspaceContains:dart.create","workspaceContains:flutter.sh.create","workspaceContains:flutter.create","onCommand:_dart.flutter.createSampleProject","onTaskType:dart","onTaskType:flutter","onUri","onDebugDynamicConfigurations"] (+127 implicit)
- contributes: menus:167, configuration:137, commands:109, views:11, viewsContainers:6, configurationDefaults:5, keybindings:3, languageModelTools:3, colors:2, taskDefinitions:2, languages:1, grammars:1, mcpServerDefinitionProviders:1, semanticTokenScopes:1, breakpoints:1, debuggers:1, problemMatchers:1
- top 10 APIs by call sites: commands.registerCommand(132), commands.executeCommand(120), Uri(109), window.showErrorMessage(55), EventEmitter(49), window.showInformationMessage(44), workspace.workspaceFolders(36), window.showWarningMessage(33), ConfigurationTarget(29), ProgressLocation(24)
- native binaries: 0 | proposals: -
- OOS parts: DAP (contributes.debuggers); CHAT (chat/lm API or contributions)
- blockers: contributes 1 debuggers (debug.dap out of scope); uses chat/lm API (LanguageModelTextPart, LanguageModelToolResult, lm.registerTool, lm.registerMcpServerDefinitionProvider); contributes chat/LM/MCP points
- spike status: not run in the spike (only 8 extensions were spiked); expected to load per D1 evidence, UNVERIFIED for this id

### PKief.material-icon-theme 5.38.1 (6,080,100 dl, rank 37)
- licence: MIT | target: universal | engines.vscode: ^1.55.0
- activation: ["onStartupFinished"] (+11 implicit)
- contributes: configuration:17, commands:11, iconThemes:1
- top 10 APIs by call sites: window.showQuickPick(6), window.showInputBox(4), commands.registerCommand(1), env.language(1), extensions.getExtension(1), window.createOutputChannel(1), window.showInformationMessage(1), workspace.getConfiguration(1), workspace.onDidChangeConfiguration(1)
- native binaries: 0 | proposals: -
- OOS parts: none
- blockers: -
- spike status: not run in the spike (only 8 extensions were spiked); expected to load per D1 evidence, UNVERIFIED for this id

### mhutchie.git-graph 1.30.0 (1,120,180 dl, rank 100)
- licence: ? | target: universal | engines.vscode: ^1.38.0
- activation: ["*"] (+10 implicit)
- contributes: configuration:110, commands:10, menus:4
- top 10 APIs by call sites: ViewColumn(14), Uri(11), commands.executeCommand(6), window.activeTextEditor(5), workspace.workspaceFolders(5), window.showQuickPick(4), version(3), window.showInformationMessage(3), workspace.createFileSystemWatcher(3), workspace.getConfiguration(2)
- native binaries: 0 | proposals: -
- OOS parts: none
- blockers: -
- spike status: not run in the spike (only 8 extensions were spiked); expected to load per D1 evidence, UNVERIFIED for this id

### usernamehw.errorlens 3.28.0 (1,060,030 dl, rank 102)
- licence: MIT | target: universal | engines.vscode: ^1.107.0
- activation: ["onStartupFinished"] (+15 implicit)
- contributes: configuration:76, colors:30, commands:15
- top 10 APIs by call sites: ThemeColor(32), commands.registerCommand(17), DiagnosticSeverity(16), window.createTextEditorDecorationType(15), languages.getDiagnostics(14), window.activeTextEditor(10), window.visibleTextEditors(9), window.showInformationMessage(8), workspace.getConfiguration(8), commands.executeCommand(7)
- native binaries: 0 | proposals: -
- OOS parts: none
- blockers: -
- spike status: not run in the spike (only 8 extensions were spiked); expected to load per D1 evidence, UNVERIFIED for this id

### streetsidesoftware.code-spell-checker 4.9.3 (1,937,197 dl, rank 80)
- licence: GPL-3.0-or-later | target: universal | engines.vscode: ^1.104.0
- activation: ["onStartupFinished"] (+73 implicit)
- contributes: configuration:102, commands:67, menus:28, views:4, configurationDefaults:4, viewsContainers:3, submenus:2, icons:1, languages:1, jsonValidation:1, terminal:1, viewsWelcome:0
- top 10 APIs by call sites: Uri(65), ConfigurationTarget(40), window.activeTextEditor(39), EventEmitter(32), Range(28), window.showInformationMessage(25), workspace.getConfiguration(19), workspace.fs(18), commands.executeCommand(17), ThemeIcon(15)
- native binaries: 0 | proposals: -
- OOS parts: none
- blockers: -
- spike status: not run in the spike (only 8 extensions were spiked); expected to load per D1 evidence, UNVERIFIED for this id
<!-- gen:end must_work_profiles -->
