# tools

Suggested addition (not in the original spec): dev-only scripts that support building/testing the repo but never ship inside `services/*` — codegen, local environment setup, release/signing scripts, the Termux/proot bootstrap-tarball builder used by `services/mobile`.

Kept as a top-level sibling of `services/`, not nested inside it, because these scripts operate *on* the services (build them, generate code into them, package their release artifacts) rather than being one — mixing "things that ship" with "things that build the things that ship" makes both harder to reason about as the repo grows.
