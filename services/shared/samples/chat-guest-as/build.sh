#!/bin/sh
# Compiles guest/assembly to the module of the Chat sample pack with AssemblyScript (needs Node.js and npm).
# The built module is committed, like the other samples, so the app's tests run it without a toolchain.
set -e
cd "$(dirname "$0")/guest"
out="../../../../mobile/app/src/main/assets/extension-samples/easyide.sample-chat/wasm"
if ! command -v npm >/dev/null 2>&1; then
    echo "npm not found: install Node.js, then run this script again" >&2
    exit 2
fi
[ -d node_modules/assemblyscript ] || npm install --no-audit --no-fund
mkdir -p "$out"
# The custom abort keeps the only import easyide.host_call; the runtime initializer is exported
# as _initialize, which the host calls under limits (a start function would be refused).
npx asc assembly/index.ts -o "$out/main.wasm" --optimize --runtime incremental --exportStart _initialize --use abort=assembly/easyide/abort
echo "wrote $out/main.wasm"
