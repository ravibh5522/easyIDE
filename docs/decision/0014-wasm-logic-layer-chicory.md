# 0014 - WASM logic layer (L2) in-app on Chicory, JSON-over-memory ABI v1 (amends 0009)

Status: Accepted (2026-09-24) - direction approved; stays conditional on the on-device Chicory spike (metering, interruption, host->guest alloc callback). Revert to Proposed if the spike fails.

## Context

[0009](0009-extension-platform-tiers.md) went straight from declarative contributions
(Tier 1) to a Node VS Code extension host (Tier 2). The Extension SDK
([extension-sdk/arch.md](../extension-sdk/arch.md), ADR-F there) needs something between
them: custom logic (a word counter, a view fed by parsing a file, a code action) that the
fixed action vocabulary of [0013](0013-extension-sdk-shape-manifest-easyext.md) cannot
express, without the memory cost of Node and without running third-party code in the
sandbox, where nothing is contained ([0002](0002-sandbox-backend-proot-default-chroot-optin.md)).

Constraints:

- **Must be in-app and pure JVM.** No NDK library to build, sign and keep ABI-compatible
  across arm64/x86_64; no new native attack surface in the app process.
- **Must be enforceable.** It is the only layer where capabilities can actually be refused
  (arch.md sec 9), so the guest must have no ambient imports - no WASI filesystem, sockets
  or clocks.
- **Must be bounded.** Memory cap, per-call fuel and wall-clock timeout, so a bad module
  cannot freeze the UI or take memory from language servers.
- Authors should be able to use Rust or AssemblyScript without our toolchain.

Runtime facts, as recorded in [sdk-reference.md#wasm-host-api](../extension-sdk/sdk-reference.md#wasm-host-api)
(checked 2026-09-23 from github.com/dylibso/chicory and its releases API): Chicory is
**Apache-2.0**, latest release **1.7.5 (2026-03-24)**, actively maintained, pure JVM with
zero native dependencies, and has an Android test suite. **Not yet verified**: performance
on ART, and whether its built-in instruction metering / thread interruption is sufficient
for our fuel and timeout model. Chicory's AOT mode generates JVM bytecode and very likely
does not apply on ART; assume the interpreter.

## Decision

Add **L2: WASM modules executed in the app process on Chicory's interpreter**, in a new
`:ext-wasm` module that isolates the dependency. ABI v1 is **core wasm, no WASI, no
Component Model**: the guest exports `memory`, `alloc`, `free`, `ext_abi_version`,
`ext_activate`, `ext_handle`; the host imports exactly one function, `easyide.host_call`,
carrying length-prefixed UTF-8 JSON. Every `host_call` is checked against the extension's
declared **and** approved capabilities. One instance and one worker thread per extension;
all limits are `extensions.wasm.*` settings keys.

This ADR stays **Proposed until an ART spike** measures interpreter throughput and
confirms fuel + timeout enforcement on a real device. If the spike fails, the fallback is
a metering pass injected at load time (same ABI); if Chicory is unusable on ART outright,
this ADR is superseded before M7 starts.

## Alternatives considered

- **wasmtime / wasmer / WAMR via JNI.** Faster (JIT/AOT), but native libraries per ABI,
  a C/Rust toolchain in our build, and native memory-safety bugs inside the app process.
  Rejected for M7; revisit only with measured need.
- **GraalWasm.** Pure JVM, but built on Truffle, whose JIT is not available on ART, and a
  much larger dependency. Rejected.
- **JS in a QuickJS/Duktape binding.** Native code again, and JS authors would expect the
  vscode API, which we do not offer at L2.
- **Skip L2; go straight to the Node host (0009 Tier 2).** Rejected: adds 100s of MB per
  host, runs third-party code in the uncontained sandbox, and still does not give us an
  enforced capability boundary.
- **WASI preview 1/2 imports.** Rejected for v1: ambient fs/clock/random imports undercut
  the "nothing but `host_call`" rule. Component Model/WIT is reconsidered as ABI v2.

## Consequences

- 0009's tier list gains a layer: L0 = Tier 0, L1 = Tier 1, **L2 WASM (new)**, L3 Node
  host = Tier 2, L4 webviews = Tier 3. 0009 is amended, not superseded.
- The capability boundary is **implemented by us over Chicory's interpreter and is not
  audited**. Docs and UI must say "enforced by easyIDE" and never "sandboxed" or "secure".
  A spawned sandbox process (`sandbox.exec`) is unconstrained once started.
- JSON-over-memory is slow compared with typed ABIs but trivial to implement in any guest
  language and to version (`v` field; additive functions within v1).
- Interpreter speed limits L2 to UI-latency logic, not heavy analysis; heavy work belongs
  in a language server.
- `host_call` is synchronous for the guest; long operations return a handle and complete
  by event. That keeps the ABI to one import but means guests cannot block on I/O.
- A new dependency (Chicory) in the APK; its license and maintenance status must be
  re-checked from primary sources at adoption and recorded in extension-sdk/arch.md.
