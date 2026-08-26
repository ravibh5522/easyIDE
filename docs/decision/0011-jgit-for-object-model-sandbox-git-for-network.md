# 0011 - Git: JGit for the object model, the sandbox's `git` for the network

Status: Accepted

## Context

Source control had to work on-device: status, staging, commits, history, and
later clone/push with an auth flow, all rendered natively.

Two candidate engines, plus one that is easy to miss.

## Decision

**JGit in-process for everything that reads or writes the local object model**
(status, stage, commit, log, refs, diff, merge), and **the guest's own `git`
inside the proot sandbox for anything that touches the network** (clone, fetch,
push).

## Alternatives considered

- **JGit for everything, including the network.** Simpler on paper. Rejected
  because the credential-helper protocol, hooks, LFS, submodules and the user's
  own `.gitconfig` all live in the sandbox, and JGit's SSH support means pulling
  in Apache MINA sshd. Those gaps surface late, after the UI is built on top.
- **Sandbox `git` for everything, parsing porcelain.** Full fidelity and no new
  dependency. Rejected for the read path: every refresh would spawn a process
  through proot's ptrace tracer, and the file watcher fires a refresh on every
  save. It also means parsing text where JGit hands over typed objects.
- **libgit2 via JNI** ([git24j](https://github.com/git24j/git24j), MIT). Rejected
  on maintenance: 56 stars, last pushed 2024-10. [jagged](https://github.com/ethomson/jagged)'s
  own README says "you probably actually want to be using jgit". Building against
  libgit2 directly means owning a JNI layer and per-ABI native builds for no gain.

## Consequences

- **JGit is a real Android dependency and was proven, not assumed.** 7.7.1,
  **EDL 1.0 = BSD-3-Clause** (read from its `LICENSE`, so no conflict with
  [0008](0008-noncommercial-source-available-licensing.md)). `java.nio.file`
  requires API 26+ and our minSdk is exactly 26 — no margin. A probe on the
  Pad 6 ran init/stage/commit/log before any UI was written.
- **The two halves must agree on one `.git`.** They do, because it is the same
  directory: real `git` in the sandbox reads the repository JGit created,
  verified with `git log --stat`. But it is now possible for the guest to change
  the repo underneath a cached handle, which is why `GitService` opens per call.
- **Auto-detection came free.** Git status refresh hangs off the existing
  `ProjectFileWatcher`, and because proot does not hide writes from the kernel,
  changes made by the terminal raise the same inotify events as editor saves.
- **The credential-helper socket becomes an immediate problem.** It sits in the
  rootfs, reachable by anything in the sandbox. That was a noted future concern
  in [0009](0009-extension-platform-tiers.md); once push ships, the thing behind
  it is a token that can write to the user's repositories. **This must be settled
  before push, not after.**
- **Commits are currently attributed to a placeholder** (`easyIDE
  <dev@easyide.local>`). A commit must have an author and refusing to commit
  would be worse, but this cannot survive contact with a real remote.
- APK grew by JGit's ~3 MB. Acceptable.
