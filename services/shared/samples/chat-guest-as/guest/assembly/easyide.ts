// Guest side of the easyIDE WASM ABI v1 for AssemblyScript (sdk-reference "WASM host API").
//
// Messages cross the boundary as [u32 little-endian length][UTF-8 JSON] buffers allocated with
// the guest's `alloc`; the receiver frees them with `free(ptr, 4 + length)`. ext_activate and
// ext_handle receive a bare (ptr, len) JSON message that the guest frees.
//
// Compile with `--use abort=<path>/easyide/abort` so the module imports nothing but
// `easyide.host_call` (the host refuses any other import).

@external("easyide", "host_call")
declare function host_call(ptr: usize, len: u32): usize;

export const ABI_VERSION: i32 = 1;

export function ext_abi_version(): i32 {
  return ABI_VERSION;
}

export function alloc(len: u32): usize {
  return heap.alloc(<usize>max<u32>(len, 1));
}

export function free(ptr: usize, len: u32): void {
  if (ptr != 0) heap.free(ptr);
}

/** Replaces AssemblyScript's `env.abort` import: a failed assertion traps the call. */
export function abort(message: string | null, file: string | null, line: u32, column: u32): void {
  unreachable();
}

function frame(json: string): usize {
  const bytes = String.UTF8.encode(json);
  const n = <u32>bytes.byteLength;
  const p = alloc(4 + n);
  store<u32>(p, n);
  memory.copy(p + 4, changetype<usize>(bytes), n);
  return p;
}

function takeFramed(ptr: usize): string {
  const n = load<u32>(ptr);
  const s = String.UTF8.decodeUnsafe(ptr + 4, n);
  free(ptr, 4 + n);
  return s;
}

function takeMessage(ptr: usize, len: u32): string {
  const s = String.UTF8.decodeUnsafe(ptr, len);
  free(ptr, len);
  return s;
}

/** Outcome of a host call or a command: raw JSON `result`, or an error code and message. */
export class Reply {
  constructor(public ok: bool, public result: string, public code: string, public message: string) {}

  static value(rawJson: string): Reply { return new Reply(true, rawJson, "", ""); }
  static error(code: string, message: string): Reply { return new Reply(false, "null", code, message); }
  static notFound(what: string): Reply { return Reply.error("E_NOT_FOUND", "unknown " + what); }
}

let nextId: i64 = 1;

/** Calls host function `fn` with `argsJson` (a JSON object as text). */
export function call(fn: string, argsJson: string = "{}"): Reply {
  const id = nextId++;
  const req = '{"v":1,"id":' + id.toString() + ',"fn":' + Json.quote(fn) + ',"args":' + argsJson + "}";
  const bytes = String.UTF8.encode(req);
  const ptr = host_call(changetype<usize>(bytes), <u32>bytes.byteLength);
  if (ptr == 0) return Reply.error("E_UNAVAILABLE", "no host");
  const resp = takeFramed(ptr);
  if (Json.field(resp, "ok") == "true") {
    const r = Json.field(resp, "result");
    return Reply.value(r != null ? r! : "null");
  }
  const e = Json.field(resp, "error");
  const err = e != null ? e! : "{}";
  const code = Json.field(err, "code");
  const msg = Json.field(err, "message");
  return Reply.error(code != null ? Json.str(code!) : "E_INTERNAL", msg != null ? Json.str(msg!) : "");
}

/** What an extension overrides; every method has a default. Arguments are raw JSON text. */
export class Extension {
  activate(info: string): string | null { return null; }             // non-null = activation error message
  command(name: string, args: string): Reply { return Reply.notFound(name); }
  event(name: string, data: string): void {}
  request(method: string, params: string): Reply { return Reply.notFound(method); }
  deactivate(): void {}
}

function answer(id: string, r: Reply): usize {
  if (r.ok) return frame('{"v":1,"id":' + id + ',"ok":true,"result":' + r.result + "}");
  return frame('{"v":1,"id":' + id + ',"ok":false,"error":{"code":' + Json.quote(r.code) + ',"message":' + Json.quote(r.message) + "}}");
}

/** Body of `ext_activate` for [ext]. */
export function activate(ext: Extension, ptr: usize, len: u32): usize {
  const failure = ext.activate(takeMessage(ptr, len));
  if (failure == null) return frame('{"v":1,"ok":true}');
  return frame('{"v":1,"ok":false,"error":{"code":"E_INTERNAL","message":' + Json.quote(failure!) + "}}");
}

/** Body of `ext_handle` for [ext]; 0 = no response (events, deactivate). */
export function handle(ext: Extension, ptr: usize, len: u32): usize {
  const msg = takeMessage(ptr, len);
  const type = Json.field(msg, "type");
  const kind = type != null ? Json.str(type!) : "";
  const idRaw = Json.field(msg, "id");
  const id = idRaw != null ? idRaw! : "null";
  if (kind == "command") {
    const c = Json.field(msg, "command"); const a = Json.field(msg, "args");
    return answer(id, ext.command(c != null ? Json.str(c!) : "", a != null ? a! : "null"));
  }
  if (kind == "request") {
    const m = Json.field(msg, "method"); const p = Json.field(msg, "params");
    return answer(id, ext.request(m != null ? Json.str(m!) : "", p != null ? p! : "null"));
  }
  if (kind == "event") {
    const e = Json.field(msg, "event"); const d = Json.field(msg, "data");
    ext.event(e != null ? Json.str(e!) : "", d != null ? d! : "null");
    return 0;
  }
  if (kind == "deactivate") ext.deactivate();
  return 0;
}

/** Just enough JSON for messages: quote a string, decode a string literal, read a top-level field. */
export namespace Json {
  export function quote(s: string): string {
    let out = '"';
    for (let i = 0; i < s.length; i++) {
      const c = s.charCodeAt(i);
      if (c == 0x22) out += '\\"';
      else if (c == 0x5c) out += "\\\\";
      else if (c == 0x0a) out += "\\n";
      else if (c == 0x0d) out += "\\r";
      else if (c == 0x09) out += "\\t";
      else if (c < 0x20) out += "\\u" + c.toString(16).padStart(4, "0");
      else out += String.fromCharCode(c);
    }
    return out + '"';
  }

  /** The text of a JSON string literal (`"a\nb"` -> a, newline, b); anything else as-is. */
  export function str(raw: string): string {
    if (raw.length < 2 || raw.charCodeAt(0) != 0x22) return raw;
    let out = "";
    let i = 1;
    while (i < raw.length - 1) {
      const c = raw.charCodeAt(i);
      if (c != 0x5c) { out += String.fromCharCode(c); i++; continue; }
      const e = raw.charCodeAt(i + 1);
      if (e == 0x6e) out += "\n";
      else if (e == 0x72) out += "\r";
      else if (e == 0x74) out += "\t";
      else if (e == 0x62) out += "\b";
      else if (e == 0x66) out += "\f";
      else if (e == 0x75) { out += String.fromCharCode(<i32>parseInt(raw.substr(i + 2, 4), 16)); i += 4; }
      else out += String.fromCharCode(e);
      i += 2;
    }
    return out;
  }

  /** Raw JSON text of `key` in the top-level object `json`, or null. */
  export function field(json: string, key: string): string | null {
    let i = skipWs(json, 0);
    if (i >= json.length || json.charCodeAt(i) != 0x7b) return null;
    i++;
    while (i < json.length) {
      i = skipWs(json, i);
      if (json.charCodeAt(i) == 0x7d) return null;
      const keyEnd = skipValue(json, i);
      const k = str(json.substring(i, keyEnd));
      i = skipWs(json, keyEnd);
      if (json.charCodeAt(i) != 0x3a) return null;
      i = skipWs(json, i + 1);
      const valueEnd = skipValue(json, i);
      if (k == key) return json.substring(i, valueEnd);
      i = skipWs(json, valueEnd);
      if (json.charCodeAt(i) == 0x2c) i++;
    }
    return null;
  }

  function skipWs(s: string, i: i32): i32 {
    while (i < s.length) {
      const c = s.charCodeAt(i);
      if (c != 0x20 && c != 0x0a && c != 0x0d && c != 0x09) break;
      i++;
    }
    return i;
  }

  /** Index just past the value starting at [i]. */
  function skipValue(s: string, i: i32): i32 {
    const c = s.charCodeAt(i);
    if (c == 0x22) {
      i++;
      while (i < s.length) {
        const d = s.charCodeAt(i);
        if (d == 0x5c) { i += 2; continue; }
        i++;
        if (d == 0x22) break;
      }
      return i;
    }
    if (c == 0x7b || c == 0x5b) {
      let depth = 0;
      while (i < s.length) {
        const d = s.charCodeAt(i);
        if (d == 0x22) { i = skipValue(s, i); continue; }
        if (d == 0x7b || d == 0x5b) depth++;
        else if (d == 0x7d || d == 0x5d) { depth--; if (depth == 0) return i + 1; }
        i++;
      }
      return i;
    }
    while (i < s.length) {
      const d = s.charCodeAt(i);
      if (d == 0x2c || d == 0x7d || d == 0x5d || d == 0x20 || d == 0x0a || d == 0x0d || d == 0x09) break;
      i++;
    }
    return i;
  }
}
