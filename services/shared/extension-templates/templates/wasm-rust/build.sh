#!/bin/sh
# Compiles guest/ to wasm/main.wasm. Needs Rust with the wasm32-unknown-unknown target:
#   rustup target add wasm32-unknown-unknown
set -e
cd "$(dirname "$0")/guest"
if ! command -v cargo >/dev/null 2>&1; then
    echo "cargo not found: install Rust from https://rustup.rs, then: rustup target add wasm32-unknown-unknown" >&2
    exit 2
fi
cargo build --release --target wasm32-unknown-unknown
mkdir -p ../wasm
cp "target/wasm32-unknown-unknown/release/{{crate}}.wasm" ../wasm/main.wasm
echo "wrote wasm/main.wasm"
