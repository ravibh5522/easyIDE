# 0012 - Git tokens travel in the git process's environment, not a credential socket

Status: Accepted

## Context

[0009](0009-extension-platform-tiers.md) flagged the credential-helper daemon -
a unix socket inside the rootfs - as a problem to settle before anything with
real credentials shipped. [0011](0011-jgit-for-object-model-sandbox-git-for-network.md)
then put clone/fetch/push in the guest's own `git`, which made it urgent: the
thing behind that socket becomes a token that can write to the user's
repositories.

The socket is reachable by **anything** running in the sandbox - the terminal,
Claude Code, any package the user installs, any future extension. proot is not a
boundary ([0002](0002-sandbox-backend-proot-default-chroot-optin.md)), so
nothing inside can be prevented from connecting to it.

## Decision

The token is passed in the **environment of the single `git` process that needs
it**, and read back by an inline credential helper:

```
git -c credential.helper='!f() { echo username=x; echo "password=$EASYIDE_GIT_TOKEN"; }; f' clone ...
```

At rest it lives in `GitCredentials`: AES-GCM under an Android Keystore key, so
the file on disk is useless without the device.

## Alternatives considered

- **The credential-helper daemon on a unix socket** (the original plan in
  `sandbox-runtime/arch.md`). Rejected: it is ambient authority. Every process in
  the sandbox can ask it for credentials, for as long as it is running, and
  nothing about proot lets us authenticate the caller.
- **Token in the remote URL** (`https://x:TOKEN@github.com/...`). Rejected
  outright: it persists into `.git/config`, the reflog, and any error message
  that echoes the URL.
- **`GIT_ASKPASS` pointing at a helper script.** Equivalent in effect, but the
  script has to exist on disk in the rootfs, which is the thing we are avoiding.
- **JGit's own `CredentialsProvider`**, keeping the token in-process entirely.
  The cleanest option on this axis - but 0011 put network operations in real git
  precisely because JGit does not carry the user's hooks, LFS and config.

## Consequences

- The token is **never written into the rootfs**, never enters a remote URL, and
  never appears on a command line (`ps` shows the helper's shell function, not
  the value). Its lifetime is one git invocation.
- **It is not airtight, and should not be described as such.** During that
  invocation another process in the same sandbox can read `/proc/<pid>/environ`.
  This is narrow authority rather than ambient authority - a smaller window, not
  a closed one. Only the chroot backend, with real uids, could close it.
- `LinuxEnvironment.start` and `SandboxShell.start` grew an `extraEnvironment`
  parameter. It is applied after the deliberate `clear()` of the inherited
  Android environment, so it cannot reintroduce host leakage.
- **Verified on device**: `git credential fill` accepts the inline helper and
  returns the token, and a real public clone completes through the same path.
  An authenticated push has **not** been run - there are no real credentials in
  this environment.
- The credential-helper daemon described in
  [sandbox-runtime/arch.md](../sandbox-runtime/arch.md) is superseded for git.
  If it is ever built for something else, this ADR is the reason it must not
  become git's path again.
