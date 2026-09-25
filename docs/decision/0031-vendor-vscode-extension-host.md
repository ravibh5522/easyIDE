# 0031 - Run VS Code's own extension host (vendored, MIT) with a thin main-thread adapter

Status: Accepted, pending the device spike gates below (supersedes decision 2 of [0030](0030-vscode-extension-host-in-sandbox.md) and amends its decision 1)

## Context

[0030](0030-vscode-extension-host-in-sandbox.md) chose to write our own `vscode` module (route 1)
and rejected porting VS Code's extension host because "it is coupled to VS Code's service container".
The program target is now >= 99% usage-weighted compatibility with the Open VSX corpus
([../vsx-compat/README.md](../vsx-compat/README.md)), which changes the arithmetic:

- The 99% weighted corpus calls 532 distinct `vscode` API symbols (330 reach only 50%); the stable
  API is 743 declarations (122 classes, 64 enums, 302 interfaces, 218 namespace members) at VS Code
  1.140.0 ([data/vscode-api.json](../vsx-compat/data/vscode-api.json),
  [research/route-spike.md](../vsx-compat/research/route-spike.md) section 5). A shim must re-derive
  the semantics of `TextDocument` sync, `WorkspaceEdit` conversion, event ordering, disposal and
  cancellation for all of them. The long tail is where 99% is lost.
- The coupling concern was tested, not assumed. The spike bundled
  `src/vs/workbench/api/node/extensionHostProcess.ts` with esbuild without editing any source
  (601 input files, 3.0 MB minified / 0.85 MB gzip), ran it on Node 22 against a fake main side built
  from VS Code's own `RPCProtocol`, and activated unmodified GitLens, Claude Code, Go, ESLint,
  Prettier, EditorConfig and Code Runner (7 of 8; the 8th only lacked configuration defaults).
  x86 proxies: hello activation 0.25-0.41 s, RSS 97-113 MB, GitLens 146 MB peak, RPC round trip
  p50 2.6 ms / p99 6.9 ms ([research/route-spike.md](../vsx-compat/research/route-spike.md) sections 2-3,
  [tools/vsx-audit/spike/](../../tools/vsx-audit/spike/)).
- Licence verified: microsoft/vscode `LICENSE.txt` is MIT; the 14 inlined npm packages are MIT and the
  vendored semver is ISC (route-spike section 6). Theia is EPL-2.0 OR GPL-2.0-only WITH
  Classpath-exception-2.0 and covers 90.4% of the 1.139 API by its own comparator (section 8).

## Decision

**Route 2b.** Ship VS Code's node extension host as an unmodified bundle built from a pinned stable
VS Code tag, and a thin JavaScript **main-thread adapter** in the guest, built from the same tag, that
implements the in-scope `MainThread*Shape` methods (about 328 of 524 methods across 53 in-scope shapes; 44 shapes need real work, the rest are deliberate no-ops or proposal-only, [design.md](../vsx-compat/design.md) section 6) and
forwards them as semantic JSON-RPC calls to the Kotlin side over stdio. Kotlin owns every UI surface
and all Android state. Out-of-scope shapes (debug, notebooks, chat/lm/mcp, testing UI until planned)
are stubbed in the adapter with a typed "not supported on easyIDE" error or a documented no-op.

Details: [../vsx-compat/design.md](../vsx-compat/design.md). What stays from 0030: decisions 3
(JSON-RPC, Content-Length framing, now between adapter and Kotlin), 4 (Kotlin main side, rendering by
app surfaces), 5 (files and processes are not proxied) and 6 (`host.run` gating, kill switch).
Decision 1 is amended: one host per open workspace session (environment + project), not per
environment, because our spawn path binds one project at `/workspace` and VS Code runs one host per
window. Node comes from [0032](0032-node-runtime-provisioning.md), not `apt`.

### Device go/no-go (run before any adapter work beyond M1)

Run the spike on the Xiaomi Pad 6 (arm64, proot, Ubuntu noble, Node 24 linux-arm64). Go if all hold:

| Gate | Go | No-go |
|---|---|---|
| G1 hello activated, compile cache warm | <= 1.5 s | > 3 s |
| G2 first cold start, no cache | <= 4 s | |
| G3 idle host RSS, hello only | <= 150 MB | > 200 MB |
| G4 RSS with GitLens + Claude Code + ESLint | <= 300 MB | |
| G5 RPC round trip p50 / p99 | <= 10 / 40 ms | |
| G6 activation of the 8 spike extensions with config defaults | 8/8 | |
| G7 native modules required at startup | none (spdlog replaced by a JS logger) | |

Thresholds are justified in route-spike section 10. If G1-G5 miss by less than 2x, strip
chat/mcp/debug/notebook from the bundle (22.5% of bytes) and re-test. If they miss by more, or G4
fails, fall back to route 1 for a declared subset and keep route 2b as an opt-in "full host".

## Alternatives considered

- **Route 1, our own `vscode` shim (0030).** No code exists yet; 532 symbols with re-derived semantics
  and unbounded compatibility bugs. Smaller in bytes (UNVERIFIED estimate 0.2-0.5 MB), but it is the
  semantic long tail, not bytes, that decides 99%.
- **Route 3a, Theia plugin-ext.** EPL/GPL licence to review against our PolyForm-NC app licence
  ([0008](0008-noncommercial-source-available-licensing.md)), 61.7k LOC plus 31 Theia packages,
  Inversify and Monaco, and a 10% API gap.
- **Route 3b, code-server / openvscode-server in a WebView.** MIT and complete, but it replaces the
  native Compose shell ([0006](0006-native-ide-shell-before-theia.md)); code-server is 208.5 MB
  unpacked and openvscode-server has been stalled since 2026-02.

## Consequences

- API coverage inside the host is VS Code's own from day one; our work is the adapter's main side
  and the Kotlin surfaces. Coverage is measured per `MainThread` method and per corpus gate
  ([../vsx-compat/test-program.md](../vsx-compat/test-program.md)).
- The RPC protocol between host and adapter is private and versioned by VS Code. Numeric proxy ids
  shift almost every release (70-144 per month up to 1.130), so the adapter is always rebuilt from the
  same tag as the host, and Kotlin never speaks VS Code's protocol. Measured churn of
  `extHost.protocol.ts` is +3 to +17 lines per release recently. Plan one upgrade per quarter, pinned
  to a stable tag.
- NOTICE.md gains rows for the vendored VS Code sources and the inlined npm packages; headers are
  kept. No third-party extension is ever bundled ([../vsx-compat/licensing-policy.md](../vsx-compat/licensing-policy.md)).
- Memory: one Node process of roughly 100-300 MB (to be confirmed by G3/G4) enters the MemoryPolicy
  budget beside language servers ([../vsx-compat/optimisation.md](../vsx-compat/optimisation.md)).
- [docs/extension-host/arch.md](../extension-host/arch.md) is re-scoped: its method list becomes the
  adapter-to-Kotlin UI protocol, with the amendments in design.md.
