# Route 2 spike: VS Code's extension host under Node with a fake main side

Evidence for [ADR 0031](../../../docs/decision/0031-vendor-vscode-extension-host.md) and
[research/route-spike.md](../../../docs/vsx-compat/research/route-spike.md). Audit tooling only, not app code.

`SP` is a work directory holding `vscode/` (a microsoft/vscode checkout, the notes pin 0b16cb97) and
`node_modules-spike/` (`npm i esbuild@0.28.2 typescript@5.9.3` plus the ext host runtime deps:
minimist, vscode-regexpp, @vscode/proxy-agent).

| File | What |
|---|---|
| `bundle.mjs` | esbuild bundle of `vs/workbench/api/node/extensionHostProcess` into `$SP/spike/out/exthost[.min].mjs`, prints size/gzip/input count |
| `build-renderer.mjs`, `renderer.ts` | the fake main side: VS Code's own `PersistentProtocol` + `RPCProtocol`, answers the handshake, records every MainThread call |
| `rss-probe.mjs` | samples RSS of the host process |
| `hello/` | hello-world extension (command + information message) |
| `calls/calls-<ext>.json` | recorded MainThread calls during activation of real Open VSX extensions (the 50-method set) |

Run: `SP=<workdir> node bundle.mjs && SP=<workdir> node build-renderer.mjs && SP=<workdir> [SPIKE_EXT=<unpacked extension dir>] node $SP/spike/out/renderer.mjs`.
Numbers from the x86 run are proxies; the device method is in docs/vsx-compat/optimisation.md.
