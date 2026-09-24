//! Sample L2 extension: `wasm-word-count.count` shows the word count of the active editor.

use easyide_guest::{export_extension, host, json, Extension, HostError, Value};

#[derive(Default)]
struct WordCount;

impl Extension for WordCount {
    fn activate(&mut self, _info: &Value) -> Result<(), String> {
        host::call("commands.register", json!({ "command": "wasm-word-count.count" })).map_err(|e| e.message)?;
        Ok(())
    }

    fn command(&mut self, command: &str, _args: &Value) -> Result<Value, HostError> {
        match command {
            "wasm-word-count.count" => {
                let text = host::call("editor.getText", json!({}))?;
                let words = count(text.as_str().unwrap_or(""));
                host::call("ui.showMessage", json!({ "text": format!("{words} words") }))?;
                Ok(json!(words))
            }
            _ => Err(HostError::not_found(command)),
        }
    }
}

fn count(text: &str) -> usize {
    text.split_whitespace().count()
}

export_extension!(WordCount);

#[cfg(test)]
mod tests {
    #[test]
    fn counts_whitespace_separated_words() {
        assert_eq!(super::count("  one two\nthree\t"), 3);
        assert_eq!(super::count(""), 0);
    }
}
