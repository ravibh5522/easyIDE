# Feature: Git — Architecture

## Overview

Source control inside easyIDE: the working tree as a reviewable list, staging,
commits, history, and eventually clone/push with an auth flow.

The design splits on one line: **JGit reads, the guest's `git` writes to the
network.** Everything that inspects the object model runs in-process; everything
that needs credentials, hooks or the user's own config runs as real git inside
the sandbox.

## Decisions

| ADR | Bearing |
|---|---|
| **0011** | JGit for the object model, sandbox `git` for network operations |
| [0002](../decision/0002-sandbox-backend-proot-default-chroot-optin.md) | proot is not a boundary — which is why the credential socket is a live problem, not a theoretical one |
| [0008](../decision/0008-noncommercial-source-available-licensing.md) | Rules out copyleft dependencies; JGit is BSD-3-Clause, which is why it qualifies |
| [0009](../decision/0009-extension-platform-tiers.md) | Flagged the credential-helper socket first; git auth makes it concrete |

## Why JGit

Verified before committing to it, not assumed:

| Option | Outcome |
|---|---|
| **JGit 7.7.1** | **EDL 1.0 = BSD-3-Clause**, read from its `LICENSE`. Pure Java, no NDK. `java.nio.file` needs API 26+; our minSdk is exactly 26. **Proven to run on the Pad 6**, not merely to compile |
| git24j (libgit2 JNI) | MIT, but 56 stars and last pushed 2024-10. Rejected |
| jagged (libgit2 JNI) | Upstream README says "you probably actually want to be using jgit" |
| libgit2 via NDK | GPL-2.0 with linking exception, so usable — but we would own the JNI layer and per-ABI builds for no gain |

## Architecture

```
Compose  SourceControlPane
              |  GitPanelState / SourceControlCallbacks
              v
         WorkspaceViewModel  <--- ProjectFileWatcher (inotify, debounced)
              |
              v
         GitService            suspend, ioDispatcher, GitResult
              |
              v
         GitRepository         JGit: status/stage/commit/log/refs
              |
              v
         .git on app-private storage
              ^
              |  the same bytes, read and written by
         guest `git` in the sandbox (clone/fetch/push, hooks, config)
```

### Why the repository handle is not cached

`GitService` opens and closes the repository per call. JGit holds file
descriptors and a cached index, and in this app the `.git` directory is written
behind our back as a matter of routine — the terminal, the Claude Code CLI and
(later) `git push` all touch it. A long-lived handle would serve stale state.
Opening is cheap next to the I/O each operation already does.

### Auto change detection

There is no polling and no second watcher. `ProjectFileWatcher` already existed
for the file tree — inotify via `FileObserver`, debounced, per expanded
directory — so the git refresh hangs off the same signal:

```kotlin
ProjectFileWatcher(onChanged = { dirs -> relistChangedDirs(dirs); refreshGit() })
```

This is why a change made **outside** the app appears in the panel: proot does
not hide writes from the kernel, so a file written by the terminal raises the
same inotify event as one written by the editor. Verified on device.

### Refresh vs. user actions

`GitPanelState.busy` gates the UI's own buttons, **not** `refreshGit()`. A
refresh triggered by an external write has to land even while a commit is in
flight, or the panel silently drifts from disk.

### Errors

`GitResult` has three cases, not two. "This project is not a repository" is an
ordinary state the panel renders as an offer to initialise — not an error. A
corrupt or half-cloned repository degrades to "no source control" rather than
taking the workspace down with it.

## Still to build

| Piece | Notes |
|---|---|
| Graph renderer | The data is done — `logAllRefs()` returns commits with parents in `TOPO` order, which is the input a lane-assignment algorithm needs. No renderer exists; nothing off-the-shelf exists for Compose either |
| Diff / merge editor | Use JGit's `ResolveMerger.getMergeResults()`, which yields base/ours/theirs chunks. Do **not** parse `<<<<<<<` markers |
| Clone + auth | OAuth **device flow** (no redirect URI, no client secret — the right fit for a tablet). Token in Keystore-backed `EncryptedSharedPreferences` |
| Push / pull | Through the guest's `git`, so the credential helper, hooks and LFS all work |
| Author identity | Commits currently use a hardcoded `easyIDE <dev@easyide.local>`. Needs a settings screen before anyone publishes |

## Open questions

1. **The credential-helper socket is reachable by anything in the sandbox.**
   Flagged in [0009](../decision/0009-extension-platform-tiers.md) as a future
   concern for extensions; git auth makes it immediate, because the thing behind
   it becomes a token that can push to the user's repositories. Abstract socket
   with a peer-credential check, or short-lived tokens — needs deciding **before**
   push ships, not after.
2. **Whose identity signs a commit?** The GitHub account from the auth flow is
   the obvious answer, but that couples committing to being logged in.
3. **Large repositories.** `status()` walks the working tree; a repository with a
   `node_modules` needs `.gitignore` respected (JGit does) and probably a cap.
   Unmeasured.
4. **Does the inotify watcher survive a large clone?** Thousands of new files in
   one event burst is a different shape from an editor save. Untested.
