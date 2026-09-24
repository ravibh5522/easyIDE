# 0014 - WASM logic layer (L2) in-app on Chicory, JSON-over-memory ABI v1 (amends 0009)

Status: Accepted (2026-09-24) - on-device spike passed every condition (metering, interruption, host->guest alloc callback, memory cap); see "Spike results (2026-09-24)". Interpreter throughput on ART is low and shapes the limits (below).

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

This ADR was conditional on an ART spike measuring interpreter throughput and confirming
fuel + timeout enforcement on a real device; the spike passed (see Spike results). If the spike fails, the fallback is
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

## Spike results (2026-09-24)

Device: OnePlus CPH2569 (Nord CE3), Snapdragon 778G (SM7325), 7.5 GB RAM, Android 15,
ART `java.vm.version` 2.1.0. Chicory **1.7.5** (latest on Maven Central, 2026-03-24),
`com.dylibso.chicory:runtime` + `wasm`; license **Apache-2.0** (POM `<licenses>` and repo
`LICENSE`, re-checked 2026-09-24); repo not archived, last push 2026-07-06. Suite:
`services/mobile/ext-wasm-device-test` `ChicoryOnArtSpikeTest` (Chicory used directly, no
easyIDE code), fixture `ext-wasm/src/testFixtures/wat/spike.wat`, run with
`:ext-wasm-device-test:connectedReleaseAndroidTest` (non-debuggable test APK; the debuggable
one measured 2-3x slower still). All 10 tests pass, as do the 6 `WasmHostDeviceTest` cases
that run the implemented host (`:ext-wasm`) on the same device.

| Condition | Result | Evidence |
|---|---|---|
| Host function calls guest `alloc` while inside `host_call` (ABI v1 re-entrancy) | **pass** | `hostFunctionCanCallBackIntoGuestAlloc`; the interpreter runs a nested `eval` per call frame, so a re-entrant export call returns cleanly into the outer frame |
| Memory cap below the module's declared max (`Instance.Builder.withMemoryLimits`) | **pass** | module max 1024 pages, host limit 16: `memory.grow` returns -1 past 16, pages stay 16. Memory is allocated per 64 KiB page on grow, not up front |
| Fuel via injected checkpoints (`global.get/i64.sub/global.set/.../if unreachable`) | **pass** | trap at the first checkpoint after fuel < 0 (`fuelExhaustionTrapsAtCheckpoint`) |
| Interruption from another thread by writing the fuel global | **pass** | loop stopped 10-13 ms after the first write with a 10 ms rewrite tick, with plain and with volatile globals (`GlobalFactory`). Rewrite every tick: the guest's read-modify-write can overwrite one store |
| Chicory native interruption (`Thread.interrupt`) | **insufficient alone** | polled only on `call`/`call_indirect` and `br`: a `br` loop stops in 1 ms, a `br_if` loop ignores it and ran to completion (2.6 s). So the metering pass is required, as the LLD assumed; `Thread.interrupt` is kept as a second signal only |
| Built-in fuel/gas metering | **absent** in 1.7.5 | only `withUnsafeExecutionListener` (per-instruction callback, documented experimental and hot-path) - not used |
| Guest stack overflow | **contained** | surfaces as `ChicoryException("call stack exhausted")`, never a process crash. Depth is bounded by the worker's JVM stack: 1000 frames (default test thread), 2000 (1 MiB), 10000 (8 MiB) |
| API 26-32 compatibility (app `minSdk` 26; Chicory's own Android suite uses 28) | **pass with rules**, not run on a < 33 device | `d8 --min-api 26` backports `List.of`/`Map.of`/`copyOf`/`requireNonNullElse`; what remains at API 33 is `ByteArrayOutputStream.writeBytes` (`WasmWriter`), VarHandles (`ByteArrayMemory`) and `VarHandle.fullFence` (atomics, with an `Unsafe` fallback). `:ext-wasm` therefore uses the default `ByteBufferMemory`, never `WasmWriter`, and refuses the threads proposal |

Measured costs (median, non-debuggable):

| What | ART (778G) | HotSpot 21 (dev laptop, same fixture) |
|---|---|---|
| Interpreter throughput, tight loop | **~7 M wasm instr/s** | ~80 M instr/s |
| Same loop with a checkpoint per iteration | 2.6 s vs 1.1 s per 1 M iterations (+129%, worst case: checkpoint as long as the loop body) | - |
| AOT `cmd package compile -m speed/everything` | no gain (4-5 M instr/s) | - |
| Parse + validate, 446 B module | 1.2 ms | - |
| Parse + validate, 1.5 MB clang-built module (wabt's `wat2wasm`) | 490 ms | 490 ms |
| Instantiate small module | 0.13 ms | - |
| Export call (`ext_abi_version`) | 1.4 us | - |
| `host_call` round trip incl. re-entrant `alloc` and a 60 B copy | 11 us | - |
| `:ext-wasm` `WasmHost`: activate (load, meter, parse, instantiate, `ext_activate`), 3 KB guest | 76 ms cold, 31 ms warm cache | - |
| `WasmHost` overhead per command (worker hop, message copy in/out, guest doing nothing) | 0.42 ms | - |
| Metering pass on the 1.5 MB module / parse of its metered output | 27 ms / 846 ms | - |
| Watchdog: 200 ms `callTimeoutMs` on a spinning guest | fired at 210 ms | - |

Consequences for the design (applied in lld/wasm-host.md and `:ext-wasm`):

- Throughput is the binding constraint: at ~6 M metered instr/s, `callTimeoutMs` 2000 is hit
  long before `fuelPerCall` 50000000 (about 8 s of guest time on this device); on ART the
  wall clock is the effective limit and fuel is the device-independent backstop. The
  sdk-reference defaults stay; revisit if M7 samples need more.
- A 4 MB `maxMessageKb` message is far beyond what a guest can parse inside 2 s on ART;
  large payloads are an author-guide warning, not a new limit.
- Load time is dominated by parsing (0.3 ms per KB): the parsed-module LRU and the metered
  bytes cache (lld sec 3.3) matter; instantiation itself is cheap.
- Worker threads get an explicit 8 MiB JVM stack (`WasmPolicy`) so guest recursion depth is
  about 10000 frames and predictable across devices.

