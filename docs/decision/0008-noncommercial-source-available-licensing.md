# 0008 - Source-available licensing: PolyForm Noncommercial + paid commercial license

Status: Accepted

## Context

The repo had no `LICENSE` file at all. Without one, default copyright applies: nobody
has any right to use, copy or modify the code, which is the opposite of the intent.

The intent was stated as: people can read and modify the code, personal use is free,
commercial/enterprise use has to be paid for. An earlier framing also wanted forks to
publish their changes back.

Two things had to be resolved before writing a file:

1. **"Free for personal, paid for enterprise" is not open source.** It fails clause 6
   of the Open Source Definition (no discrimination against fields of endeavor). Any
   license that encodes it is *source-available*, and calling it "open source" in a
   README or store listing would be a false claim.
2. **The three original requirements cannot all be satisfied by one license.**
   Copyleft (publish changes back) and a noncommercial field-of-use restriction are
   different mechanisms, and no mainstream license combines them. AGPL-3.0 gives
   publish-back but legally *permits* free enterprise use; noncommercial licenses
   forbid enterprise use but do not force publish-back. One had to give.

Nothing in the dependency tree constrained the choice: all current dependencies are
Apache-2.0 (Kotlin, AndroidX, Compose, Material). Theia -- the one copyleft-adjacent
dependency in the plan -- is deferred per [0006](0006-native-ide-shell-before-theia.md)
and in any case only imposes file-level EPL-2.0 obligations on modified Theia files
per [0001](0001-ide-foundation-theia.md).

## Decision

easyIDE's own source is licensed under **PolyForm Noncommercial License 1.0.0**,
with a separate **paid commercial license** offered by the copyright holder for any
commercial use. Contributions are taken under an inbound **CLA** granting the licensor
sublicensing rights, which is what makes the dual-licensing legally possible.

The publish-back requirement was **dropped**, not encoded. Enterprise-must-pay was the
stronger of the two goals, and PolyForm Noncommercial is the license that states it
plainly rather than achieving it as a side effect of copyleft unpleasantness.

Files: [LICENSE](../../LICENSE), [LICENSE-COMMERCIAL.md](../../LICENSE-COMMERCIAL.md),
[CONTRIBUTING.md](../../CONTRIBUTING.md), [NOTICE.md](../../NOTICE.md).

## Alternatives considered

- **AGPL-3.0 + CLA + commercial dual license** (Grafana, GitLab, Sentry's original
  model). The only option that legally *forces* forks to publish changes, and it keeps
  OSI "open source" branding. Rejected because it does not actually forbid enterprise
  use -- a compliant enterprise can use it free forever, and revenue depends entirely
  on their legal department finding AGPL unacceptable. That is a real business model,
  but it is not what was asked for.
- **BSL 1.1** (MariaDB, Terraform, CockroachDB). Encodes "free for personal, paid for
  production" literally via an Additional Use Grant. Rejected for its mandatory Change
  Date: BSL requires the code to convert to an open source license within four years.
  That gives away the commercial position on a timer, for goodwill this project does
  not yet need.
- **Elastic License 2.0 / FSL.** Only restrict competing or managed-service use;
  ordinary internal enterprise use stays free. Too permissive for the stated goal.
- **A custom license combining noncommercial terms with a publish-back clause.** Would
  satisfy all three original requirements. Rejected: bespoke license text has no case
  law, no tooling recognition (SPDX, GitHub, dependency scanners), and no lawyer has
  reviewed it. Enterprises refuse to buy against unfamiliar terms, which defeats the
  purpose.

## Consequences

- Enterprise use is now a **sale**, not a compliance question. The line is drawn in
  [LICENSE-COMMERCIAL.md](../../LICENSE-COMMERCIAL.md); ambiguous cases route to the
  licensor rather than being resolved by the reader.
- **Forks do not have to publish their changes.** This is the accepted cost. A company
  can fork privately for noncommercial use and never contribute back. Revisit only via
  a new ADR -- switching to copyleft later requires the CLA to already be in place,
  which is the main reason it is being collected from the first contribution.
- **The CLA will deter some contributors.** Signing away sublicensing rights to a
  commercial product is a real ask. [CONTRIBUTING.md](../../CONTRIBUTING.md) states
  this in plain language instead of burying it.
- **"Open source" must not appear** in the README, Play Store listing, or marketing.
  Correct terms: *source-available*, *free for personal and noncommercial use*.
- **Copyleft obligations from bundled binaries survive regardless of easyIDE's own
  license.** PRoot is GPL-2.0-or-later and talloc is LGPL-3.0-or-later, both shipped in
  the APK. Because PRoot is exec'd as a separate process rather than linked, this is
  aggregation and does not infect easyIDE's Kotlin source -- but a written offer of
  source for both is owed to every recipient, including paying customers. Recorded in
  [NOTICE.md](../../NOTICE.md). **If PRoot is ever linked into the app process instead
  of exec'd, this ADR must be reopened.**
- The Claude Code CLI cannot be vendored into the APK under any of this -- it is
  Anthropic's commercial software and users must install it under their own account.
- Not legal advice. The CLA in particular should be reviewed by a lawyer before the
  first external contribution is merged, and the copyright holder line in
  [LICENSE](../../LICENSE) should name a legal entity if one is later incorporated.
