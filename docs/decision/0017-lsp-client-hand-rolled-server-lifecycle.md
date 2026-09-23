# 0017 - LSP client: hand-rolled JSON-RPC in the app process; one server per (environment, project, server)

Status: Proposed

## Context

Language intelligence (diagnostics, completion, hover, rename, ...) comes from LSP 3.17
servers running inside the sandbox (Extension SDK arch.md sec 7, ADR-I). Three questions
had to be settled before M2:

1. **Where does the client run?** In the app process, or inside the sandbox next to the
   server ([extensions/arch.md](../extensions/arch.md) open question 4).
2. **Library or hand-rolled?** Eclipse LSP4J is the standard JVM implementation. It is
   **EPL-2.0 OR EDL-1.0** (verified 2026-09-23), brings Gson and reflection-heavy
   generated types, and we need roughly 40 methods of the protocol.
3. **How many servers?** Every project binds to the same guest path `/workspace`
   ([0005](0005-sandbox-environment-sharing-model.md)), so one server process cannot see
   two projects at once; and memory is the binding constraint on an 8 GB tablet
   ([0009](0009-extension-platform-tiers.md) fact 3).

## Decision

- The **client runs in the app process** in a new `:lsp` module (Kotlin, no Android UI
  dependencies, JVM-testable). The sandbox holds only the disposable server process,
  spawned over **stdio** by a `ServerProcessFactory` beside `ShellRunner` in
  `:sandbox-runtime`, with a clean environment plus the server's declared `env`.
- **JSON-RPC framing and the LSP types we use are hand-rolled**, on the JSON library
  already in the app - no LSP4J, no new dependency.
- **One session per key `(environmentId, projectId, serverKey)`**, started lazily on the
  first open document of a language, stopped after `lsp.idleShutdownSec`, restarted with
  backoff on crash, and evicted by a single `MemoryPolicy` kill order under
  `lsp.globalMemoryBudgetMb` / per-server `memoryBudgetMb` / `lsp.maxServers`
  (state machine and kill order in arch.md sec 7.6-7.7).
- The client advertises only capabilities the UI renders at the current milestone.

## Alternatives considered

- **Client inside the sandbox** (a small bridge process speaking to the app over a
  socket). Rejected: editor buffers, decorations and settings are in-app, so every
  keystroke would cross a second IPC hop, and it adds a long-lived process we would have
  to keep alive under Android's process killer.
- **LSP4J.** Complete and well tested, but Gson + reflection on ART, a large method
  count, and EPL-2.0 file-level obligations for a job that is mostly framing and ~40 data
  classes. Rejected; revisit if the hand-rolled surface grows past what we can maintain.
- **One server per (environment, server) shared across projects.** Rejected: all
  projects appear as `/workspace`, so the server would see only one at a time; switching
  projects would mean re-initializing anyway, with stale state risks.
- **One server per open file / no sharing within a project.** Rejected: multiplies memory
  for no benefit; servers are designed to own a workspace.
- **TCP or WebSocket transport.** Rejected for v1: every candidate server supports stdio,
  and a listening port inside an uncontained sandbox is reachable by every process there.

## Consequences

- **We own a protocol implementation.** Every new LSP feature means new types and tests
  here; conformance is tested against a fake LSP server in JVM tests, not assumed.
- JVM-only `:lsp` means the whole client (framing, sync, cancellation, supervisor state
  machine) is testable without a device.
- Opening two projects in the same environment runs two copies of the same server. The
  memory policy, not the architecture, is what keeps that bounded; the kill order never
  touches terminal sessions or editor buffers.
- Servers get a clean environment: the git token ([0012](0012-git-token-in-process-env-not-credential-socket.md))
  and other app secrets never reach them. Servers themselves are uncontained sandbox
  processes ([0002](0002-sandbox-backend-proot-default-chroot-optin.md)) - disclosure only.
- Environment files (stdlib, site-packages) are mapped to host paths and opened
  **read-only** in the editor via `PathMapper`.
- Future multi-root maps extra folders to `/workspace/<name>` binds with no protocol
  change, because `workspaceFolders` is advertised from the start.
