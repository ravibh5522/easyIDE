# Agents (sample)

Chat sessions with a command line agent, and the files that changed, as an easyIDE extension made of a manifest, view files and
shell scripts, no code. It shows the `chat` and `composer` components, a document type with a state provider, two containers
(a sidebar and a secondary sidebar) and a layout preset.

- **Agents** (workspace scope): sessions list with **New session**; a session opens as a document with the conversation.
- A message is sent to `claude -p` when the environment has the claude CLI, otherwise to a documented **echo agent** that repeats
  what it was sent. Set `easyide.sample-agents.command` to use any other command: it gets the message in `$AGENT_PROMPT` and
  answers on standard output. The app makes no network call itself; whatever the agent command does is what it does.
- Sessions are plain files in the project, `.easyide/agents/<id>.jsonl` and `<id>.title`; delete them like any file.
- **Changes** (secondary sidebar) lists `git status`; tap a file to open it.
- Replies arrive whole, not word by word: an action returns when the command has finished. A WebAssembly provider can stream
  by calling `ui.setViewData` repeatedly; the chat component appends and follows either way.
- Capabilities: `sandbox.exec` (the scripts and the agent), `fs.project(read)` (open a changed file), `network(api.anthropic.com)`
  (what the claude CLI uses; shown, not enforced, for processes in the environment), `ui.contribute`, `ui.stage`.
