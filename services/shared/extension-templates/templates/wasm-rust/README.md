# {{displayName}}

A WASM (L2) extension written in Rust with the easyIDE guest bindings (`guest/easyide-guest`).

```sh
./build.sh                      # guest/ -> wasm/main.wasm (needs the wasm32-unknown-unknown target)
easyide-ext validate --strict   # includes the app's static WASM check
easyide-ext package
```

The module runs in the app with only the capabilities `package.json` declares; `editor.getText`
needs `fs.project(read)`. Host functions: sdk-reference "WASM host API".
