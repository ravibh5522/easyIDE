# Docker (sample)

A working easyIDE extension made of a manifest, three view files and three shell scripts, no code: it drives the `docker` CLI
inside the environment.

- Navigation item **Docker** (workspace scope) with the number of running containers as its badge.
- **Containers** and **Images** views in a sidebar container; tap a container to open its document (logs, environment,
  start, stop, restart, remove).
- Data comes from `bin/*.sh` through `sandboxExec` while a view is on screen (every 3 to 15 seconds); nothing runs in the
  background. Logs are the last 200 lines, refreshed, not a live `docker logs -f`.
- Capabilities: `sandbox.exec` (it runs the docker CLI and its scripts), `ui.contribute` (screens), `ui.stage` (view data).
  It needs the docker CLI and a reachable daemon (for example `DOCKER_HOST` pointing at a remote one); without them the
  views say so.
