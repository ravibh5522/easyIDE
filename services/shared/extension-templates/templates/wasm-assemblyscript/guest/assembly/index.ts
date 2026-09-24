// {{displayName}}: `{{name}}.wordCount` shows the word count of the active editor.
import { Extension, Reply, call, activate, handle, Json } from "./easyide";
export { alloc, free, ext_abi_version } from "./easyide";

class Ext extends Extension {
  activate(info: string): string | null {
    const r = call("commands.register", '{"command":"{{name}}.wordCount"}');
    return r.ok ? null : r.message;
  }

  command(name: string, args: string): Reply {
    if (name != "{{name}}.wordCount") return Reply.notFound(name);
    const text = call("editor.getText");
    if (!text.ok) return text;
    const words = countWords(Json.str(text.result));
    call("ui.showMessage", '{"text":' + Json.quote(words.toString() + " words") + "}");
    return Reply.value(words.toString());
  }
}

function countWords(s: string): i32 {
  let n = 0;
  let inWord = false;
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i);
    const space = c == 0x20 || c == 0x0a || c == 0x0d || c == 0x09;
    if (!space && !inWord) n++;
    inWord = !space;
  }
  return n;
}

// Created on first use: a module-level `new` would compile to a WASM start function, which the
// host refuses (it would run before the call limits are armed).
let instance: Ext | null = null;
function ext(): Ext {
  if (instance == null) instance = new Ext();
  return instance!;
}

export function ext_activate(ptr: usize, len: u32): usize {
  return activate(ext(), ptr, len);
}

export function ext_handle(ptr: usize, len: u32): usize {
  return handle(ext(), ptr, len);
}
