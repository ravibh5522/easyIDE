# Word Count (WASM sample)

A minimal L2 extension written in Rust with the `easyide-guest` crate
(`services/shared/guest-rust`). `./build.sh` compiles `guest/` to `wasm/main.wasm`; the built
module is committed so the app's WASM host tests can run it without a Rust toolchain.

```sh
./build.sh
easyide-ext validate . --strict   # includes the app's static WASM check
easyide-ext test .
easyide-ext package .
```
