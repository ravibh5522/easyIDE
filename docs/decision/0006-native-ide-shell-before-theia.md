# 0006 - Ship a native IDE shell now; Theia becomes the language-intelligence layer later

Status: Accepted

## Context

[0003](0003-multi-stage-panels-theia-widgets.md) decided that Theia's `ApplicationShell` would own docking and that stages would host Theia widgets, so no native panel system should be built. Following that literally produced a Workspace screen made of four empty placeholder panes: it could not open a file, edit anything, or run a command, and would stay that way until a whole Node userland was provisioned inside a sandbox that does not exist yet.

That is a long time to have no working product, and it made the app impossible to evaluate.

## Decision

Build the IDE shell natively in Compose **now** - activity rail, file explorer, tabbed editor with a line-number gutter and regex syntax colouring, terminal pane, status bar - operating on real files in the project directory. Theia is retained as the eventual **language-intelligence and extension** layer, mounted into the main stage when the sandbox can run Node, not as the thing that supplies basic editing.

The terminal runs commands through Android's own `/system/bin/sh` (`ShellRunner`). That is a genuine shell with the app's UID - toybox applets work - and it is honest about being a command runner rather than a PTY.

## Alternatives considered

- **Wait for Theia** (the 0003 position). Correct on capability, wrong on sequencing: it front-loads the hardest dependency (proot bootstrap, Node, extension host) before anything is usable, and leaves no way to validate the UI, navigation, persistence or file handling in the meantime.
- **Native shell as a throwaway demo, deleted when Theia lands.** Rejected - the native path is also the offline/low-memory path, and on a tablet a native explorer and editor will stay faster than a WebView. It earns its place beyond the interim.
- **WebView with a JS editor (CodeMirror/Monaco) bundled as an asset**, no Node needed. A real option and closer to VS Code's editor, but it drags in a WebView for the core editing surface and a JS bridge for file I/O, which is most of Theia's cost without Theia's benefit.

## Consequences

- 0003 is **partially superseded**: its widget/`WidgetFactory` mechanism is still the right way to add PDF/docx/video renderers *once Theia is mounted*, but native Compose panes - not Theia dock areas - own the shell today. The "stages" vocabulary in [/docs/ui-shell/arch.md](../ui-shell/arch.md) now maps to native panes.
- Known gaps, deliberately: no PTY (no interactive vim/htop, no job control), no LSP, no git UI, and syntax colouring is regex-based, not a parser. Each closes when the sandbox runtime lands.
- The editor loads whole files into memory and caps at 2 MB (`ProjectFiles.MAX_EDITABLE_BYTES`); large-file editing needs a windowed buffer before that cap can rise.
- Every file operation is confined to the project directory by canonical-path checks in `ProjectFiles` - the same guard the tar extractor uses - so a crafted relative path cannot reach another project.
