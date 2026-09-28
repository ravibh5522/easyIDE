# 0032 - License easyIDE's own source under AGPL-3.0

Status: Accepted (2026-09-28)

## Context

[0008](0008-noncommercial-source-available-licensing.md) chose PolyForm Noncommercial. The owner replaced it with
the GNU Affero General Public License v3 (AGPL-3.0), which is the GPL v3 plus section 13: a modified version that
users interact with over a network must offer those users its source.

The owner is the copyright holder, and the CLA in [CONTRIBUTING.md](../../CONTRIBUTING.md) grants the right to
sublicense contributions, so contributions can be licensed this way.

## Decision

easyIDE's own source is licensed under **AGPL-3.0**. `LICENSE` is the unmodified text from
https://www.gnu.org/licenses/agpl-3.0.txt (checked 2026-09-28). README, CONTRIBUTING, NOTICE and the release
workflow say AGPL.

## Alternatives considered

- **A permissive or weaker copyleft license.** Rejected by the owner: a hosted or network-served fork could keep
  its changes private.
- **AGPL-3.0 plus a commercial dual license.** The model 0008 described; possible later because the CLA allows it,
  not set up now.

## Consequences

- Builds `0.2.0-beta.3` to `0.2.0-beta.5` were withdrawn and replaced by `0.2.0-beta.6`, the first release whose
  assets carry the AGPL `LICENSE`. Copies people had already downloaded keep the terms they were received under.
- Unchanged: the Apache-2.0 SDK modules ([0015](0015-extension-sdk-licensing-apache.md)), vendored Apache-2.0
  Termux terminal code ([0010](0010-pty-terminal-vendored-termux.md)), and PRoot (GPL-2.0-or-later) and talloc
  (LGPL-3.0-or-later), which run as separate processes or shared libraries and keep their own terms in NOTICE.md.
- The Firebase Analytics binaries are proprietary ([0031](0031-telemetry-firebase-behind-neutral-module.md)); they
  are separate components, not our source, but a strict reading of copyleft on bundling them is unresolved.
- Section 13 matters only if easyIDE's code is run as a network service; the app itself is not one.
- Many companies bar AGPL dependencies outright, which limits corporate reuse. That is the trade-off.
- This is not legal advice; an owner planning commercial licensing should have counsel review it.
