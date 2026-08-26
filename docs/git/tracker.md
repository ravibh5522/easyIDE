# Feature: Git — Tracker

Status legend: `not-started` / `in-progress` / `done` / `blocked`

Design: [arch.md](arch.md). Local source control works end to end; nothing touches
the network yet.

## Decisions

| # | Decision | Status |
|---|---|---|
| [0011](../decision/0011-jgit-for-object-model-sandbox-git-for-network.md) | JGit for the object model, sandbox `git` for network ops | decided |
| Commit author identity | Hardcoded placeholder today; needs a settings screen | **open — blocks publishing** |
| [0012](../decision/0012-git-token-in-process-env-not-credential-socket.md) | Token via the git process's environment, not the credential socket | decided |

## Components

| Component | Status | Notes |
|---|---|---|
| JGit on Android | **done** | 7.7.1, BSD-3-Clause, builds + dexes + **runs** on the Pad 6 |
| `GitRepository` | **done** | open/init, status, stage, unstage, discard, commit, log, logAllRefs, branches, originUrl |
| `GitService` | **done** | Suspend API on an IO dispatcher, per-call repo handle, three-state `GitResult` |
| Source Control panel | **done** | Staged/unstaged/conflicting sections, per-row stage/unstage/discard, stage-all, commit box, A/M/D/U/C markers, branch in header |
| Activity-rail destination | **done** | Third rail entry; clicking the active one collapses the panel |
| Auto change detection | **done** | Hangs off the existing `ProjectFileWatcher`; catches writes made outside the app |
| Branch in status bar | **done** | |
| Open a change in the editor | **done** | `openFileByPath` — git reports paths that may not be in the lazily-expanded tree |
| Diff view | **not-started** | Tapping a row opens the file, not a diff |
| Graph renderer | **done** | `CommitGraph` lane assignment + per-row `Canvas` gutter inside the panel's LazyColumn. Lane colours come from the syntax tokens so it tracks the active theme |
| Merge editor | **not-started** | Use `ResolveMerger.getMergeResults()`, not conflict markers |
| Token storage (`GitCredentials`) | **done** | AES-GCM under an Android Keystore key; no `security-crypto` dependency |
| Remote ops (`GitRemote`) | **done, not wired to UI** | clone/fetch/pull/push through the guest's real `git`. **Mechanism verified on device**; no screen calls it yet |
| Clone / push UI | **not-started** | Nothing in the app can enter a URL or a token, so clone and push are unreachable by a user |
| OAuth device flow | **blocked** | Needs a registered GitHub OAuth app **client_id**, which only the project owner can create. PAT entry is the shippable path meanwhile |
| Push / pull | **done, not wired to UI** | Same path as clone. **Never run against a real remote** - no credentials to test with |
| Branch switching / create | **not-started** | `branches()` reads them; nothing writes |
| Author identity settings | **not-started** | Commits are attributed to `easyIDE <dev@easyide.local>` |

## Verification status

**Verified on the Xiaomi Pad 6** (Android 13, arm64, enforcing) on 2026-08-11 by
driving the real UI:

| Check | Result |
|---|---|
| JGit runtime round-trip | init -> untracked -> stage -> commit -> modified -> log, all correct |
| Panel on a non-repo project | "Not a repository" + Initialise |
| Initialise | Branch `main`, 3 untracked files listed, `src/app.js` shows its subdirectory |
| Stage all | Files move to STAGED CHANGES with `A`; commit button enables and counts |
| Commit | List clears, message clears, `.git` written |
| **Interop: real git reads the JGit repo** | `git log --stat` in the sandbox shows the commit, author and `3 files changed, 44 insertions(+)` |
| **Auto-detection from outside the app** | Files written via `run-as` appeared as `U` and `M` with no refresh tap |

Also verified 2026-08-11, headlessly in the sandbox:

| Check | Result |
|---|---|
| Graph vs. real git | Branch/merge history built with real `git`; the app's lanes match `git log --graph` - feature commits in lane 1, merge elbow, lane rejoining at the base commit |
| Credential-helper syntax | `git credential fill` returns `username=x` and the password from `$EASYIDE_GIT_TOKEN` - git accepts the inline helper |
| Real network clone | `octocat/Hello-World` cloned through the same code path; `git log` shows `7fd1a60`, README on disk |

**Not verified: an authenticated push or a private clone.** That needs a real
token for a real account, which this environment does not have. The transport is
the same code path as the public clone above, but treat push as unproven.

Also unmeasured: large repositories, and the watcher under a clone-sized burst
of inotify events.
