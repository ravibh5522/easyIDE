//! {{displayName}}: `{{name}}.wordCount` shows the word count of the active editor.

use easyide_guest::{export_extension, host, json, Extension, HostError, Value};

#[derive(Default)]
struct Ext;

impl Extension for Ext {
    fn activate(&mut self, _info: &Value) -> Result<(), String> {
        host::call("commands.register", json!({ "command": "{{name}}.wordCount" })).map_err(|e| e.message)?;
        Ok(())
    }

    fn command(&mut self, command: &str, _args: &Value) -> Result<Value, HostError> {
        match command {
            "{{name}}.wordCount" => {
                let text = host::call("editor.getText", json!({}))?;
                let words = text.as_str().unwrap_or("").split_whitespace().count();
                host::call("ui.showMessage", json!({ "text": format!("{words} words") }))?;
                Ok(json!(words))
            }
            _ => Err(HostError::not_found(command)),
        }
    }
}

export_extension!(Ext);
