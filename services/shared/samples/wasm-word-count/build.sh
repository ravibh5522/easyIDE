#!/bin/sh
# Builds guest/ into wasm/main.wasm (needs the wasm32-unknown-unknown Rust target).
set -e
cd "$(dirname "$0")/guest"
cargo build --release --target wasm32-unknown-unknown
cp target/wasm32-unknown-unknown/release/wasm_word_count.wasm ../wasm/main.wasm
