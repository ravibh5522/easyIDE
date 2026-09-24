#!/bin/sh
# Compiles guest/assembly to wasm/main.wasm with AssemblyScript (needs Node.js and npm).
set -e
cd "$(dirname "$0")/guest"
if ! command -v npm >/dev/null 2>&1; then
    echo "npm not found: install Node.js, then run this script again" >&2
    exit 2
fi
[ -d node_modules/assemblyscript ] || npm install --no-audit --no-fund
mkdir -p ../wasm
# The custom abort keeps the only import easyide.host_call; the runtime initializer is exported
# as _initialize, which the host calls under limits (a start function would be refused).
npx asc assembly/index.ts -o ../wasm/main.wasm --optimize --runtime incremental --exportStart _initialize --use abort=assembly/easyide/abort
echo "wrote wasm/main.wasm"
