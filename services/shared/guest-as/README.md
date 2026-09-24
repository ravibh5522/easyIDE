# @easyide/guest-as

AssemblyScript bindings for easyIDE WASM extensions, ABI v1 (`docs/extension-sdk/sdk-reference.md`
"WASM host API"). Apache-2.0 (decision 0015). Copy `assembly/easyide.ts` into your project, or
install this package, and re-export the ABI entry points from your entry file:

```ts
import { Extension, Reply, call, activate, handle, Json } from "./easyide";
export { alloc, free, ext_abi_version } from "./easyide";

class WordCount extends Extension {
  command(name: string, args: string): Reply {
    if (name != "acme.wordCount") return Reply.notFound(name);
    const text = call("editor.getText");
    if (!text.ok) return text;
    const words = Json.str(text.result).split(" ").filter((w) => w.length > 0).length;
    call("ui.showMessage", '{"text":' + Json.quote(words.toString() + " words") + "}");
    return Reply.value(words.toString());
  }
}

// Create lazily: a module-level `new` compiles to a start function, which the host refuses.
let instance: WordCount | null = null;
function ext(): WordCount { if (instance == null) instance = new WordCount(); return instance!; }
export function ext_activate(ptr: usize, len: u32): usize { return activate(ext(), ptr, len); }
export function ext_handle(ptr: usize, len: u32): usize { return handle(ext(), ptr, len); }
```

Compile so the module imports only `easyide.host_call` and exports its runtime setup as `_initialize`
(the host calls it under limits; a start function is refused):

```sh
npx asc assembly/index.ts -o wasm/main.wasm --optimize --runtime incremental --exportStart _initialize --use abort=assembly/easyide/abort
```

Values cross as raw JSON text: `Json.quote` builds strings, `Json.str` decodes a string literal and
`Json.field` reads one top-level field. For richer JSON use a library such as `json-as`.
