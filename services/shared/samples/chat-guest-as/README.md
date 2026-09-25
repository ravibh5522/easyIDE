# Chat sample: guest module

AssemblyScript source of `wasm/main.wasm` in the Chat sample pack
(`services/mobile/app/src/main/assets/extension-samples/easyide.sample-chat`). `./build.sh` compiles it there; the built module is
committed so the app's tests run it without Node.js. `guest/assembly/easyide.ts` is the ABI v1 binding shipped in
`services/shared/guest-as`.
