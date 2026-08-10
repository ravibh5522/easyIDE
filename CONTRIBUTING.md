# Contributing to easyIDE

Contributions are welcome. Please read the licensing section first -- easyIDE is
dual-licensed, and that has a real consequence for contributors.

## Licensing of contributions

easyIDE is offered under two licenses at once: the
[PolyForm Noncommercial License](LICENSE) for everyone, and a
[paid commercial license](LICENSE-COMMERCIAL.md) for commercial users. That only
works if a single party holds the rights to relicense the whole codebase. So:

**By submitting a pull request, patch, or any other contribution, you agree to
the Contributor License Agreement below.**

There is nothing to sign or email. Opening a PR is the acceptance.

### Contributor License Agreement

1. **Copyright grant.** You grant the licensor (Ravi, and any legal entity they
   later assign this project to) a perpetual, worldwide, non-exclusive,
   royalty-free, irrevocable copyright license to reproduce, prepare derivative
   works of, publicly display, publicly perform, **sublicense**, and distribute
   your contribution and derivative works of it, **under any license terms,
   including commercial and proprietary terms**.

2. **Patent grant.** You grant the licensor and recipients of the software a
   perpetual, worldwide, non-exclusive, royalty-free, irrevocable patent license
   to make, use, sell, offer to sell, import and otherwise transfer your
   contribution, covering only those patent claims you can license that are
   necessarily infringed by your contribution alone or by its combination with
   this project. If you institute patent litigation alleging that this project or
   a contribution to it constitutes patent infringement, any patent licenses
   granted to you for this project terminate as of the date the litigation is
   filed.

3. **You keep your copyright.** This is a license, not an assignment. You may
   continue to use your own contribution however you wish, including in other
   projects and for commercial purposes.

4. **Representations.** You represent that each contribution is your original
   work, that you are legally entitled to grant the above licenses, and that if
   your employer has rights to work you create, you have either received
   permission to contribute on their behalf or your employer has waived those
   rights. If any part of your contribution is not your original work, say so
   explicitly in the pull request and include the source and its license.

5. **No warranty.** You provide your contribution on an "as is" basis, without
   warranties or conditions of any kind.

### What this means in practice

The licensor can ship your contribution to paying commercial customers. You will
not be paid for it. If you are not comfortable with that, do not contribute code
-- issues, reproductions and design feedback are still very welcome and are not
covered by the CLA.

### Third-party code

Do not paste code from another project into a pull request. If a third-party
component is genuinely the right answer, propose it as a dependency instead, and
include its license and maintenance status **verified from the upstream
repository or official docs, not from memory**. Anything copyleft or
non-permissive needs to be discussed before the PR, because it may conflict with
the commercial license. Adopted dependencies get recorded in [NOTICE.md](NOTICE.md).

## Before you open a pull request

The repo conventions live in [.claude/CLAUDE.md](.claude/CLAUDE.md) and
[docs/README.md](docs/README.md). The ones that most often come up:

- **600 lines max per file.** Over that, split by responsibility rather than
  compressing the code.
- **No hardcoding.** Colors, spacing, strings, icons, keybindings and thresholds
  belong in a single declarative source per concern, never inlined at the call site.
- **Error handling only at real boundaries** -- file I/O, the credential-helper
  socket, the WebView/Kotlin bridge, sandbox process spawning, network calls.
  Not defensively around pure logic.
- **Extract at the third real use**, not the first.
- **New feature area** -> scaffold `docs/<feat-name>/arch.md` and `tracker.md`.
  **Major, hard-to-reverse decision** -> an ADR in [docs/decision/](docs/decision/).
  **Any non-trivial change** -> an entry in this week's [docs/chainlog/](docs/chainlog/) file.
- **Never describe proot or chroot+BusyBox as security isolation** in code, UI
  copy or docs. Both are single-tenant, trust-the-code-you-run models -- see
  [decision 0002](docs/decision/0002-sandbox-backend-proot-default-chroot-optin.md).
- **Verify before claiming done.** Run the build and tests that actually exist in
  your environment. If something cannot be verified (no tablet, no emulator), say
  so in the PR rather than reporting success.

## Reporting a security issue

Do not open a public issue. Email **ravibh5522@gmail.com** with the details and
allow time for a fix before disclosing.
