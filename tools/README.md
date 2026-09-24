# tools

Suggested addition (not in the original spec): dev-only scripts that support building/testing the repo but never ship inside `services/*` — codegen, local environment setup, release/signing scripts, the Termux/proot bootstrap-tarball builder used by `services/mobile`.

Kept as a top-level sibling of `services/`, not nested inside it, because these scripts operate *on* the services (build them, generate code into them, package their release artifacts) rather than being one — mixing "things that ship" with "things that build the things that ship" makes both harder to reason about as the repo grows.

## Contents

| Path | What | License |
|---|---|---|
| `build-grammars.py` | Builds the bundled TextMate grammar assets for `services/mobile` | PolyForm NC (repo default) |
| [`easyide-ext/`](easyide-ext/) | Extension author CLI: init, validate, package, keygen, sign, verify | **Apache-2.0** ([0015](../docs/decision/0015-extension-sdk-licensing-apache.md)) |
