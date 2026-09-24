# {{displayName}}

A WASM (L2) extension written in AssemblyScript with the easyIDE guest bindings
(`guest/assembly/easyide.ts`).

```sh
./build.sh                      # guest/ -> wasm/main.wasm (needs Node.js and npm)
easyide-ext validate --strict   # includes the app's static WASM check
easyide-ext package
```

Values cross the host boundary as raw JSON text: `Json.quote`, `Json.str` and `Json.field` cover
simple messages. The module runs with only the capabilities `package.json` declares.
