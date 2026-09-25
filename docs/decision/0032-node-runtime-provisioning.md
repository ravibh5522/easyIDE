# 0032 - Node runtime for the extension host: pinned official Node 24 linux-arm64 in the guest

Status: Accepted (amends decision 1 of [0030](0030-vscode-extension-host-in-sandbox.md): "Node comes from `apt-get install nodejs`")

## Context

- VS Code builds with Node 24.18.0 (`.nvmrc`) and targets 24.21.0 for its server (`remote/.npmrc`); its
  extension host calls `module.registerHooks` unconditionally, which needs Node >= 22.15
  ([research/route-spike.md](../vsx-compat/research/route-spike.md) section 7,
  [research/policy-licence.md](../vsx-compat/research/policy-licence.md) section 3).
- The guest is Ubuntu 24.04 (noble), whose archive ships nodejs 18.19.1, end of life on 2025-04-30
  (policy-licence section 3). Corpus floors are higher: GitLens `engines.node` >= 24, Code Spell
  Checker >= 22.20, GitHub Pull Requests >= 20.
- Official linux-arm64 builds: v24.21.0 is 30.8 MB `.tar.xz`, v22.23.3 is 30.2 MB; they need glibc
  >= 2.28 (Node BUILDING.md); noble has 2.39. Integrity comes from SHASUMS256.txt, signed with the
  Node release keys (policy-licence section 3).

## Decision

The app pins one Node 24 LTS version and its SHA-256. On first enable of a code extension, a sandbox
step downloads the official `node-v<ver>-linux-arm64.tar.xz` from nodejs.org, checks the pinned
SHA-256, and unpacks it to `/opt/easyide/node/<ver>/` in that environment. The host is launched with
that absolute path and never uses `node` from `PATH`, so a user's own Node (apt, nvm) cannot break
it and ours does not replace theirs. `NODE_COMPILE_CACHE` points at a guest cache directory. Node
upgrades ship with app releases (new pin), keeping the previous directory until the new host has
started once.

## Alternatives considered

- **`apt-get install nodejs` (0030).** Gives 18.19.1 on noble: too old for the host.
- **NodeSource apt repo.** Has arm64 builds (24.21.0, 38 MB), but adds a third-party apt source with a
  signing key we have not verified, and floats with `apt upgrade`.
- **Bundle Node in the APK.** About 30 MB more APK for every user, including those who never enable a
  code extension. It is also a native executable delivered through the APK and run inside proot,
  which [0034](0034-play-policy-stance-code-extensions.md) keeps behind a channel flag.
- **Upgrade the guest image to Ubuntu 26.04 (Node 22.22.1).** Couples the extension host to the
  image catalog ([0007](0007-sandbox-image-catalog-and-custom-rootfs.md)) and still lags VS Code's Node 24.

## Consequences

- About 31 MB download and about 100 MB unpacked (UNVERIFIED; measure on device) per environment that
  enables code extensions.
- V8's JIT under proot on the device is UNVERIFIED; it is gate G1/G2 of [0031](0031-vendor-vscode-extension-host.md).
- The Node licence (MIT, with third-party notices) is shown in the licences screen; Node is downloaded,
  not redistributed by us.
- Extensions that spawn `node` themselves (e.g. via `process.execPath`) get our pinned binary, as
  they would get VS Code's.
