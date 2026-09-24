// Chat (sample): a local notes-style chat store on the extension storage API.
//
// Rooms are keys `room.<name>` of the global storage scope, each a JSON array of `{id, role: "user", text}` messages. The module keeps
// no state of its own between calls: every command reads what it needs from storage, so an instance that was dropped and
// re-instantiated (a trap, memory pressure) loses nothing.
//
//   easyide.sample-chat.load  {uri, key}   -> {title, messages}     the document's state provider
//   easyide.sample-chat.send  {room, text} -> {messages}            appends a note and returns the room
//   easyide.sample-chat.clear {room}       -> {messages: []}
//
// On activation and after every change it also pushes `{rooms: [{name}]}` to the Rooms view with ui.setViewData.
import { Extension, Reply, call, activate, handle, Json } from "./easyide";
export { alloc, free, ext_abi_version } from "./easyide";

const SCOPE: string = '"scope":"global"';
const ROOM_PREFIX: string = "room.";
const SEQ_KEY: string = "seq";
const ROOMS_VIEW: string = "easyide.sample-chat.rooms.list";
const DEFAULT_ROOMS: string[] = ["general", "ideas", "todo"];

function storageGet(key: string): string {
  const r = call("storage.get", '{' + SCOPE + ',"key":' + Json.quote(key) + "}");
  return r.ok ? r.result : "null";
}

function storageSet(key: string, valueJson: string): Reply {
  return call("storage.set", '{' + SCOPE + ',"key":' + Json.quote(key) + ',"value":' + valueJson + "}");
}

/** The messages of [room] as a JSON array text; an unknown room is an empty one. */
function messages(room: string): string {
  const raw = storageGet(ROOM_PREFIX + room);
  return raw.length > 1 && raw.charCodeAt(0) == 0x5b ? raw : "[]";
}

/** [array] with [item] added at the end; both are JSON text, the array is one this module wrote. */
function append(array: string, item: string): string {
  if (array == "[]") return "[" + item + "]";
  return array.substring(0, array.length - 1) + "," + item + "]";
}

/** The room names: the defaults, then every stored room, each once. */
function roomList(): string {
  const names = new Array<string>();
  for (let i = 0; i < DEFAULT_ROOMS.length; i++) names.push(DEFAULT_ROOMS[i]);
  const keys = call("storage.keys", "{" + SCOPE + "}");
  if (keys.ok) {
    // keys.result is a JSON array of strings: read each literal.
    let i = 0;
    const s = keys.result;
    while (i < s.length) {
      if (s.charCodeAt(i) != 0x22) { i++; continue; }
      let j = i + 1;
      while (j < s.length && s.charCodeAt(j) != 0x22) j += s.charCodeAt(j) == 0x5c ? 2 : 1;
      const key = Json.str(s.substring(i, j + 1));
      if (key.startsWith(ROOM_PREFIX)) {
        const name = key.substring(ROOM_PREFIX.length);
        if (!names.includes(name)) names.push(name);
      }
      i = j + 1;
    }
  }
  let out = "[";
  for (let k = 0; k < names.length; k++) out += (k > 0 ? "," : "") + '{"name":' + Json.quote(names[k]) + "}";
  return out + "]";
}

function pushRooms(): void {
  call("ui.setViewData", '{"viewId":' + Json.quote(ROOMS_VIEW) + ',"items":{"rooms":' + roomList() + "}}");
}

/** The object a command was called with: the host hands a command its arguments as an array, and a view passes one object. */
function argObject(args: string): string {
  const at = args.indexOf("{");
  return at < 0 ? "{}" : args.substring(at);
}

class Chat extends Extension {
  activate(info: string): string | null {
    const commands: string[] = ["easyide.sample-chat.load", "easyide.sample-chat.send", "easyide.sample-chat.clear"];
    for (let i = 0; i < commands.length; i++) {
      const r = call("commands.register", '{"command":' + Json.quote(commands[i]) + "}");
      if (!r.ok) return r.message;
    }
    pushRooms();
    return null;
  }

  command(name: string, rawArgs: string): Reply {
    const args = argObject(rawArgs);
    if (name == "easyide.sample-chat.load") {
      const key = Json.field(args, "key");
      const room = key != null ? Json.str(key!) : "";
      return Reply.value('{"title":' + Json.quote("# " + room) + ',"messages":' + messages(room) + "}");
    }
    const roomRaw = Json.field(args, "room");
    const room = roomRaw != null ? Json.str(roomRaw!) : "";
    if (room.length == 0) return Reply.error("E_ARGS", "room is required");
    if (name == "easyide.sample-chat.send") {
      const textRaw = Json.field(args, "text");
      const text = textRaw != null ? Json.str(textRaw!) : "";
      if (text.trim().length == 0) return Reply.value('{"messages":' + messages(room) + "}");
      const seqRaw = storageGet(SEQ_KEY);
      const seq = seqRaw == "null" ? 1 : I32.parseInt(seqRaw) + 1;
      const note = '{"id":"m' + seq.toString() + '","role":"user","text":' + Json.quote(text) + "}";
      const updated = append(messages(room), note);
      const saved = storageSet(ROOM_PREFIX + room, updated);
      if (!saved.ok) return saved;
      storageSet(SEQ_KEY, seq.toString());
      pushRooms();
      return Reply.value('{"messages":' + updated + "}");
    }
    if (name == "easyide.sample-chat.clear") {
      const saved = storageSet(ROOM_PREFIX + room, "[]");
      if (!saved.ok) return saved;
      pushRooms();
      return Reply.value('{"messages":[]}');
    }
    return Reply.notFound(name);
  }
}

// Created on first use: a module-level `new` would compile to a WASM start function, which the
// host refuses (it would run before the call limits are armed).
let instance: Chat | null = null;
function ext(): Chat {
  if (instance == null) instance = new Chat();
  return instance!;
}

export function ext_activate(ptr: usize, len: u32): usize {
  return activate(ext(), ptr, len);
}

export function ext_handle(ptr: usize, len: u32): usize {
  return handle(ext(), ptr, len);
}
