# 0023 - Workspace session lifetime: an app-scoped registry parks projects; a hot-exit snapshot survives the process

Status: Accepted (2026-09-24)

## Context

ux-overhaul S1 / ADR-D ([ux-overhaul/arch.md](../ux-overhaul/arch.md) Pillar 2). A workspace's terminals (real
pty subprocesses), open buffers, tab order, carets, scroll offsets and stage layout lived in a
`WorkspaceViewModel` scoped to its navigation entry. Back popped the entry, cleared the ViewModel,
killed every shell and dropped unsaved text. S1 first added a Save all / Discard / Cancel prompt on
leaving, which stops the silent loss but makes "glance at the project list" cost a decision, and
still killed a running dev server. The process itself can also die (Android reclaims a backgrounded
app, the user swipes it away, a crash), and nothing was persisted at all.

Constraints: proot and chroot are trust-the-code-you-run, single-tenant models
([0002](0002-sandbox-backend-proot-default-chroot-optin.md)), so this is about durability, not
isolation. A pty subprocess is a child of the app process: when Android kills the process the whole
tree goes with it, whatever runs inside (sandbox-runtime arch SS4 says the same about tmux).

## Decision

1. **Sessions belong to the process, keyed by project id.** `WorkspaceRegistry` (app-scoped, in
   `AppContainer`) holds one `WorkspaceViewModel` per project in a `ViewModelStore` of its own.
   Leaving a project (Back, Home, opening Settings) **parks** it: shells keep running, buffers stay in
   memory. Entering it again re-attaches the same session, exactly as left. At most one workspace is
   active; attaching another parks the first. A screen that is animating out cannot park the workspace
   its replacement re-attached (attach returns a lease, park needs the newest one).
2. **Leaving does not prompt.** Parking loses nothing, so the S1 leave guard is gone. The Save all /
   Discard / Cancel dialog now guards **explicit close** (a new "Close project" button on the activity
   rail), the one act that ends a session. Discard in that dialog means discard: the stored session and
   its backups are deleted. Closing a tab with unsaved edits still asks (S2).
3. **Park limit and eviction.** More than `workspace.maxParkedProjects` (default 2, range 0-8) parked
   workspaces: the least recently active is saved then ended. Under `onTrimMemory`: from
   `RUNNING_CRITICAL` (excluding `UI_HIDDEN`, which is not pressure) the LRU parked workspace, at
   `COMPLETE` all parked ones; never the active one. Eviction always writes a backup first and does not
   discard it, and a re-opened project restores only after that final save (the registry hands the new
   session the previous one's still-running job).
4. **Hot-exit snapshot.** Per project, in app-private storage (`files/sessions/<id>/session.json` plus
   `backups/<hash of path>.txt` for each dirty buffer): open tabs in order, active tab, caret, scroll,
   markdown preview flag, expanded folders, stage layout. Every write is temp file + fsync + atomic
   rename; backups are written before the snapshot that references them and unreferenced ones pruned
   after. Written 2 s after a change (a timer, not a debounce, so typing without pauses still backs up),
   when a workspace is parked or evicted, and when the activity stops (a bounded blocking flush in
   `onStop`). Terminals are not persisted: a shell is a process and a restore starts a fresh one.
5. **Format and compatibility.** JSON with `v`; a reader accepts any `v >= 1`, ignores unknown keys and
   defaults missing ones, so a newer build's file restores in an older build and vice versa. A change that
   cannot be additive uses a new file name. Corrupt or unreadable files count as "no session".
6. **Restore.** Tabs reopen from disk. A tab that had unsaved edits gets its text back and is classified
   against the disk by the SHA-256 of the text the edits were based on: unchanged base restores dirty,
   edits already on disk restore clean, anything else restores dirty with a conflict; a missing file
   restores dirty and "gone". A non-blocking "Restored N unsaved files" notice follows. `workspace.restoreOpenTabs`
   (default on) governs clean tabs, folders and cursor state only: **unsaved text is always restored**, since
   it exists nowhere else. `workspace.openLastProjectOnLaunch` (default off) opens the most recently opened
   project on a cold start, with Home underneath, and is skipped while a crash report is pending so a
   project that crashes the app cannot loop.
7. **External changes (S8).** Open editable tabs are compared by text with the disk when the watcher reports
   their directory (the watcher now also covers directories of open tabs) and when the workspace is shown
   again. A clean buffer reloads silently with a notice; a dirty one whose file changed becomes a conflict
   (Keep mine / Use disk version / Decide later, save is blocked until chosen); a deleted file keeps its
   buffer, flagged. Renaming or moving a file or folder in the explorer retargets every affected tab.
8. **Foreground service ownership.** `SandboxForegroundService` runs while at least one workspace is live
   and stops with the last (`SandboxKeepAlive`, driven by the registry). It owns no processes; it is what
   asks Android to keep the app process at foreground priority so the shells survive backgrounding. Its
   notification says how many projects are running and offers "Stop all" (ends every session, saving each
   first). It is `START_NOT_STICKY`: a restarted service would find no sessions, they died with the process.
   It handles Android 15's six-hour `dataSync` timeout by stopping itself.
9. **Not built: tmux.** Running each tab in a per-project `tmux` session would let tabs re-attach after the
   *pty* is lost, but not after the process is killed (the tmux server is a child of it too), needs tmux to
   be installed in every rootfs, and turns "close tab" into a kill-versus-detach choice. It buys nothing the
   registry and snapshot do not, so the tracker row stays open and this is the reason.

## Alternatives considered

- **Keep the nav-entry ViewModel and only prompt on leave (S1 as shipped)** - loses running shells on every
  Back, and a prompt per glance at the project list trains users to press Discard.
- **`NavHost` back stack kept alive (`saveState`/`restoreState`)** - saves composition state but the
  ViewModelStore is still cleared when an entry is popped without restoring, and it says nothing about the
  process dying.
- **A Service that owns the sessions (bound service)** - the service and the UI share one process anyway, so
  the extra binder layer buys no isolation; the registry is the owner and the service only asks for priority.
- **Room for session state** - already ruled out ([0005](0005-sandbox-environment-sharing-model.md)); a few
  small files with atomic rename are simpler, inspectable and need no schema migration.
- **Prompt on eviction** - eviction happens when the user is not there (or under pressure); a backup that
  restores with a notice is the only option that never blocks and never loses text.

## Consequences

- Leaving a project costs memory and keeps its shells and servers running; `workspace.maxParkedProjects`
  and `onTrimMemory` bound it, and the foreground notification makes it visible and stoppable.
- A process kill loses running commands and scrollback but no unsaved text and no tab state (at most two
  seconds of edits since the last timer, none since the last `onStop`).
- Extension host attach/detach moves with the workspace that is on screen (`onResumed`/`onParked`); the
  extension runtime still has exactly one active workspace.
- Not verified on a device: the foreground service behaviour, `onTrimMemory` delivery and process-death
  restore are covered by JVM tests of the logic only.
- The foreground service type stays `dataSync` (see the comment in the manifest); it must be revisited
  against Play policy (`specialUse` has no daily budget) before a store release.
