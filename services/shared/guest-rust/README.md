# easyide-guest

Rust bindings for easyIDE WASM extensions, ABI v1 (`docs/extension-sdk/sdk-reference.md`
"WASM host API"). Apache-2.0, like the rest of the SDK (decision 0015).

```rust
use easyide_guest::{export_extension, host, Extension, HostError, Value, json};

#[derive(Default)]
struct WordCount;

impl Extension for WordCount {
    fn activate(&mut self, _info: &Value) -> Result<(), String> {
        host::call("commands.register", json!({ "command": "acme.wordCount" })).map_err(|e| e.message)?;
        Ok(())
    }

    fn command(&mut self, command: &str, _args: &Value) -> Result<Value, HostError> {
        match command {
            "acme.wordCount" => {
                let text = host::call("editor.getText", json!({}))?;
                let n = text.as_str().unwrap_or("").split_whitespace().count();
                host::call("ui.showMessage", json!({ "text": format!("{n} words") }))?;
                Ok(Value::Null)
            }
            _ => Err(HostError::not_found(command)),
        }
    }
}

export_extension!(WordCount);
```

Build with `cargo build --release --target wasm32-unknown-unknown` (crate type `cdylib`) and point
`easyide.wasm.module` at the `.wasm`. `export_extension!` provides every ABI export: `memory` comes
from the Rust toolchain, `alloc`/`free`, `ext_abi_version` (1), `ext_activate`, `ext_handle`.
