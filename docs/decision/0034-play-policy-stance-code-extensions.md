# 0034 - Code-running extensions are a per-distribution-channel flag; Play stance pending legal review

Status: Proposed (engineering posture accepted; the Play default is decided by counsel's answers)

## Context

Running Open VSX extensions adds, on top of what the product already does (proot executables shipped
as `lib*.so` in jniLibs; a downloaded Ubuntu rootfs; user `apt-get install`, ADR
[0002](0002-sandbox-backend-proot-default-chroot-optin.md)): a downloaded Node runtime
([0032](0032-node-runtime-provisioning.md)), extension JavaScript loaded at run time, `.node` addons
and ELF executables inside `.vsix` files (72 addons and 52 executables across 32 of 156 corpus
extensions, about 33% of download weight), binaries fetched by extensions themselves, webviews with a
bridge, and a browsable catalogue. Google Play's Device and Network Abuse policy says an app "may not
download executable code (such as dex, JAR, .so files) from a source other than Google Play", with an
exception for "code that runs in a virtual machine or an interpreter where either provides indirect
access to Android APIs". No clause names IDEs, terminals or Linux userlands. Verbatim clauses, the
delta D-1..D-8, channel options O-A..O-E and 20 questions for counsel are in
[../vsx-compat/licensing-policy.md](../vsx-compat/licensing-policy.md) section 4. We do not draw a
legal conclusion.

## Decision

1. Everything that executes third-party code is behind build-config flags per distribution flavour:
   `extensions.code.enabled` (extension host and `host.run` installs) and `extensions.code.nativeAllowed`
   (`.vsix` files with native addons or executables), with `sandbox.apt.enabled` decided in the same place.
   On the Play flavour the flags can only be lowered at run time, never raised.
2. Until counsel answers licensing-policy.md section 4.6, the **Play flavour ships with
   `extensions.code.enabled = false`** (option O-A): declarative `.vsix` content (themes, grammars,
   snippets, icon themes, language configuration) still installs. Direct-download builds may ship the
   flag on (option O-C). Which of O-A..O-E is final is recorded here after the review.
3. Regardless of channel: every download is user-initiated with a disclosure sheet; no auto-update and no
   silent dependency installs; no self-update path in the Play build; webview bridges use
   `addWebMessageListener`, never `addJavascriptInterface` ([0033](0033-extension-webview-security-model.md));
   the UI says "Not available in this version of easyIDE" and never claims policy compliance.

## Alternatives considered

- **Everything on in all channels (O-E).** Carries all 20 open questions onto the Play listing.
- **Drop code extensions entirely.** Abandons the program's goal for every channel because of one.
- **Runtime opt-in on Play (O-B).** Possible after review; whether an opt-in changes the analysis is
  question L-3.

## Consequences

- The compatibility program is measured on a build with the flag on; the Play build's user-visible
  scope is the declarative subset until this ADR is updated.
- Two distribution channels mean two signing and update paths (owner decision on package ids).
- WP-SEC-15 (channel flags) and WP-SEC-18 (legal review package) in
  [../vsx-compat/security-licensing.md](../vsx-compat/security-licensing.md) implement this.
