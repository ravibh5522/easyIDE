# 0016 - Extension registry: static signed git index, ed25519 publisher keys, TOFU pins, revocation

Status: Accepted (2026-09-24) - root key is held offline by the project owner; Ed25519 availability at minSdk 26 still to confirm before M6.

## Context

Extensions installed from a registry run install commands and language servers in the
sandbox, where nothing is contained ([0002](0002-sandbox-backend-proot-default-chroot-optin.md)).
A malicious or tampered package can do anything the user can in that environment, so the
distribution channel is the main line of defence (Extension SDK arch.md sec 9, ADR-H).

Constraints:

- **No backend.** `services/backend` stays empty until a concrete need exists
  (`.claude/CLAUDE.md`); a registry must not be the reason to start one.
- **Offline first.** Tablets are used on trains; install, update and rollback must work
  from cache with no network (arch.md goal G6).
- **Publishing must be open** to third parties without us running an account system.
- **Compromise has to be survivable**: a stolen publisher key or a malicious version must
  be revocable without an app update.
- The Microsoft Marketplace is unavailable ([0009](0009-extension-platform-tiers.md));
  Open VSX is a secondary, labelled source only.

## Decision

The registry is a **git repository served as static files**: `index.json`,
`publishers/<publisher>.json`, `revocations.json`, each with a detached `.sig`. Formats in
[sdk-reference.md#registry-index-format](../extension-sdk/sdk-reference.md#registry-index-format).

- **ed25519** throughout. Signed bytes are RFC 8785 canonical JSON.
- A **registry root key**, pinned in the APK via `extensions.registries`, signs the index,
  publisher files and revocations. It is held offline.
- Each index entry is signed by a **publisher key** listed in its publisher file; the
  entry carries the package `sha256` and `size`.
- **TOFU pin** per device: first install pins `publisher -> keyId`; a different key is
  accepted only through a `rotation` record signed by the old key.
- **Any verification failure is a hard stop** - no "install anyway" for registry packages.
- **Publishing is a pull request** against the index repo (`easyide-ext publish`),
  reviewed before merge. **Updates are never automatic**: notify, show the capability
  delta, user taps.
- **Revocation** disables an installed revoked version at the next index refresh and
  notifies; it never auto-uninstalls (sandbox changes need the user).

## Alternatives considered

- **A registry service with accounts and upload API.** Rejected: needs a backend, an
  auth system and on-call; contradicts the "no backend" rule for no gain in integrity.
- **Open VSX as the primary registry.** Rejected: it carries only its own sha256, cannot
  express `easyide` capabilities or sandbox installs, and has no easyIDE-publisher key
  model. Kept as an opt-in secondary source (`extensions.openVsx.enabled`), labelled.
- **Sigstore / keyless signing.** Attractive, but verification needs online transparency
  log checks, which breaks offline install, and adds a large client dependency.
- **GPG/PGP signatures.** Rejected: key format and tooling complexity for authors, and a
  heavier verifier; ed25519 is small and in the JDK since 15 (API 33+ on Android to
  verify; a pure-Kotlin/Java fallback may be needed for minSdk 26 - to check at M6).
- **Package signing only (no root-signed index).** Rejected: without a signed index an
  attacker controlling the host can serve an old, vulnerable-but-validly-signed version
  or hide a revocation.

## Consequences

- Integrity and provenance are strong; **safety of the code itself is not claimed**.
  A validly signed pack can still run a harmful install step; the defence is PR review,
  capability disclosure and revocation, and the UI must say so.
- The root key is a single point of trust. Losing it means shipping a new pinned key in
  an app update; leaking it means every client trusts the attacker until then. Custody
  (who, where, offline) is an open question (arch.md sec 14 q4) that blocks M6.
- Index size grows with the ecosystem; a single `index.json` is fine for hundreds of
  entries and will need sharding later (a `schemaVersion` bump).
- PR-based publishing puts review load on maintainers and adds latency for authors.
- Sideloaded packages stay possible: verified against `<file>.easyext.sig` when present,
  otherwise labelled "unsigned", never auto-updated.
- Freshness is bounded by how often the client refreshes the index; offline, the UI shows
  the index age rather than pretending it is current.
