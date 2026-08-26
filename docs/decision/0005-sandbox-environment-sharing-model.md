# 0005 - Sandbox environments are shareable, projects bind into them

Status: Accepted

## Context

Every project needs a Linux environment to run in (git, compilers, Node, Claude Code CLI). The naive model is one rootfs per project, but a full Termux/distro rootfs plus an installed toolchain is hundreds of megabytes and minutes of provisioning. A user with five Node projects should not pay that five times.

At the same time, a user sometimes *does* want isolation between projects - an experiment that installs conflicting system packages should not break a working project.

Earlier docs described "one sandbox, organizationally divided by directory" ([0002](0002-sandbox-backend-proot-default-chroot-optin.md)). That is too coarse: it gives no way to opt into a separate environment at all.

## Decision

Model **environments and projects as separate entities with a many-to-one relationship**:

- A **`SandboxEnvironment`** is a provisioned rootfs plus whatever the user installed into it. It has its own id, backend ([0002](0002-sandbox-backend-proot-default-chroot-optin.md)), and provisioning state.
- A **`ProjectRecord`** references exactly one `environmentId`. Several projects may reference the same one.
- When creating a project the user picks: **reuse an existing environment** (the default when one exists - shares the toolchain, costs nothing extra) or **create a new one** (a clean rootfs, provisioned from scratch).

**Project directories live outside every rootfs** and are bind-mounted in at a fixed guest path:

```
<files>/environments/<envId>/rootfs      the Linux userland
<files>/projects/<projectId>              source, bind-mounted to /workspace
```

This layout is the load-bearing part of the decision. Because sources are never inside a rootfs:
- reassigning a project to a different environment is a metadata change plus a different bind mount - no copying, no risk of losing work;
- deleting an environment cannot delete anybody's source code;
- one environment serving many projects needs no special casing - each launch binds a different host directory to the same guest path.

**Deleting an environment is refused while any project still references it** (`SandboxError.EnvironmentInUse`, which names the dependent projects). Reassign or delete those projects first. The alternative - cascading, or silently orphaning projects - turns one destructive click into an unrecoverable one.

## Alternatives considered

- **One rootfs per project, always.** Simplest mental model and maximum isolation, but pays full provisioning cost and disk per project. Rejected as the default; it remains available as "create a new environment".
- **Exactly one global sandbox for everything** (the earlier implied model). Cheapest, but offers no escape hatch when one project's system packages break another. Rejected: no opt-out.
- **Copy-on-write / overlay-based environment cloning** so a new environment starts as a cheap diff of an existing one. Attractive, but OverlayFS is unavailable to an unprivileged Android app - the same kernel limitation that rules out real containers ([0002](0002-sandbox-backend-proot-default-chroot-optin.md)). Revisit only for the rooted chroot backend, where it might be feasible.

## Persistence note (revisit trigger)

Environments and projects are stored as a single JSON snapshot in DataStore, with a hand-written codec (`PersistedStateJson`), not Room.

Reason: at the time of writing, Kotlin 2.4.10 has no matching KSP release and the kotlinx-serialization plugin only has an RC, so both codegen routes to Room would have pinned the project to older tooling or to pre-release plugins. For a dataset of tens of rows read whole and written whole, a snapshot store is also simply adequate.

`db/migration-device/` therefore holds no Room migrations yet. **Switch to Room when any of these becomes true**: the data grows past a few hundred rows, a screen needs partial/indexed queries instead of loading everything, or two writers need row-level rather than whole-file atomicity. `SandboxStore` is the only type that knows the storage format, so the swap is contained.

## Consequences

- Environment sharing must be *visible*, or users will not understand why deleting one is refused - Home shows an environment badge with a "+N" shared count, and Settings lists each environment with its dependent-project count.
- `EnvironmentState` (NOT_PROVISIONED / PROVISIONING / READY / FAILED) is part of the model rather than inferred from disk, so a half-extracted rootfs is visibly broken instead of looking usable and failing later in a shell.
- Provisioning is not yet wired to a real bootstrap tarball; `BootstrapSource` is an interface with asset- and file-backed implementations so the eventual download/verify step is a new implementation, not a change to `EnvironmentManager`.
- A "fork this environment" feature (clone rather than share) has no cheap implementation under proot and is deliberately not offered - see the CoW alternative above.
