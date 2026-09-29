# 0033 - Every terminal mounts the environment's projects at /projects/<name>

Status: Accepted (2026-09-29)

## Context

An environment is one Linux userland shared by several projects ([0005](0005-sandbox-environment-sharing-model.md)), but a terminal only saw its own project, at the fixed path `/workspace`. Two consequences: tools that key state by absolute path (Claude Code history, trust and memory) treated every project of an environment as one, and an agent in one project could not find or read a sibling project.

## Decision

Each terminal bind-mounts **every project of its environment** at `/projects/<folder>` and starts in its own. `<folder>` is the project's name with unsafe characters replaced by `-`; clashes get `-2`, `-3`, the oldest project keeping the plain name (`ProjectMounts`). `/workspace` stays bound to the current project, because extension paths, language servers and snippet variables are defined against it.

Only projects of the same environment appear: the environment is the OS, and a project on another environment is a different OS.

## Alternatives considered

- **Project id as the folder name** - stable under renames but unreadable to people and agents. Rejected: the point is to be findable.
- **Mount only the current project** - the previous behaviour; no cross-project access.
- **Re-point `/workspace` per project only** - keeps every project's state under one path, which is the collision being fixed.

## Consequences

- `ls /projects` lists the environment's projects; `claude --add-dir /projects/<name>` gives an agent a sibling.
- Renaming a project changes its folder, so Claude's per-project history under the old path is no longer found for it. Adding a project never renames another.
- An agent can edit sibling projects. That is consistent with [0002](0002-sandbox-backend-proot-default-chroot-optin.md): the sandbox is single-tenant and trusts what runs in it.
- Mounts are added per launch (a few `-b` arguments); the project list is read from the store at launch.
- Only interactive and command terminals get the siblings; language servers and captured `sandboxExec` processes still see just `/workspace`.
