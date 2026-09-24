//! Guest side of the easyIDE WASM ABI v1.
//!
//! Messages cross the boundary as `[u32 little-endian length][UTF-8 JSON]` buffers allocated
//! with the guest's `alloc`; the receiver frees them with `free(ptr, 4 + length)`. The host
//! passes `ext_activate`/`ext_handle` a bare `(ptr, len)` JSON message that the guest frees.

pub use serde_json::{json, Value};

/// ABI version this crate implements; `ext_abi_version` returns it.
pub const ABI_VERSION: i32 = 1;

/// An error answered to the host (`{"ok":false,"error":{code,message}}`) or returned by a host call.
#[derive(Debug, Clone, PartialEq)]
pub struct HostError {
    /// One of `E_CAPABILITY`, `E_ARGS`, `E_NOT_FOUND`, `E_TIMEOUT`, `E_CANCELLED`, `E_LIMIT`,
    /// `E_UNAVAILABLE`, `E_INTERNAL`.
    pub code: String,
    pub message: String,
}

impl HostError {
    pub fn new(code: &str, message: impl Into<String>) -> Self {
        HostError { code: code.to_owned(), message: message.into() }
    }
    pub fn not_found(what: &str) -> Self { Self::new("E_NOT_FOUND", format!("unknown {what}")) }
    pub fn args(message: impl Into<String>) -> Self { Self::new("E_ARGS", message) }
    pub fn internal(message: impl Into<String>) -> Self { Self::new("E_INTERNAL", message) }
}

/// What an extension implements. Every method has a default so a module can handle only
/// what its manifest declares.
pub trait Extension {
    /// `ext_activate`: `info` has `extensionId`, `version`, `apiVersion`, granted
    /// `capabilities`, `settings` and `env`. An `Err` fails activation.
    fn activate(&mut self, _info: &Value) -> Result<(), String> { Ok(()) }
    /// A contributed command (`contributes.commands`) was invoked.
    fn command(&mut self, command: &str, _args: &Value) -> Result<Value, HostError> { Err(HostError::not_found(command)) }
    /// A subscribed event (`events.subscribe`) arrived.
    fn event(&mut self, _name: &str, _data: &Value) {}
    /// A provider request such as `provider.completion`; answer with the LSP result shape.
    fn request(&mut self, method: &str, _params: &Value) -> Result<Value, HostError> { Err(HostError::not_found(method)) }
    /// The host is about to drop the instance.
    fn deactivate(&mut self) {}
}

/// Calls the host (`host_call`).
pub mod host {
    use super::{abi, HostError, Value};
    use serde_json::json;
    use std::sync::atomic::{AtomicU64, Ordering};

    #[cfg(target_arch = "wasm32")]
    #[link(wasm_import_module = "easyide")]
    extern "C" {
        fn host_call(ptr: *const u8, len: u32) -> *mut u8;
    }

    /// Off wasm32 (unit tests of guest logic) there is no host.
    #[cfg(not(target_arch = "wasm32"))]
    unsafe fn host_call(_ptr: *const u8, _len: u32) -> *mut u8 { core::ptr::null_mut() }

    static NEXT_ID: AtomicU64 = AtomicU64::new(1);

    /// `fn` with `args`; the `result` on success. Long operations return `{"handle":n}` and
    /// complete by event.
    pub fn call(function: &str, args: Value) -> Result<Value, HostError> {
        let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
        let req = serde_json::to_vec(&json!({ "v": super::ABI_VERSION, "id": id, "fn": function, "args": args }))
            .map_err(|e| HostError::internal(e.to_string()))?;
        let ptr = unsafe { host_call(req.as_ptr(), req.len() as u32) };
        if ptr.is_null() {
            return Err(HostError::new("E_UNAVAILABLE", "no host"));
        }
        let resp = unsafe { abi::take_framed(ptr) }.map_err(HostError::internal)?;
        if resp["ok"] == Value::Bool(true) {
            Ok(resp.get("result").cloned().unwrap_or(Value::Null))
        } else {
            let e = &resp["error"];
            Err(HostError::new(e["code"].as_str().unwrap_or("E_INTERNAL"), e["message"].as_str().unwrap_or("")))
        }
    }

    /// `log.write`.
    pub fn log(level: &str, message: &str) {
        let _ = call("log.write", json!({ "level": level, "message": message }));
    }
}

/// Memory and framing; used by [`export_extension!`]. Not part of the stable API.
#[doc(hidden)]
pub mod abi {
    use super::{Extension, HostError, Value};
    use serde_json::json;

    pub fn alloc(len: u32) -> *mut u8 {
        let mut v = Vec::<u8>::with_capacity(len.max(1) as usize);
        let p = v.as_mut_ptr();
        core::mem::forget(v);
        p
    }

    /// # Safety
    /// `ptr` must come from [`alloc`] with capacity `len`.
    pub unsafe fn free(ptr: *mut u8, len: u32) {
        if !ptr.is_null() {
            drop(Vec::from_raw_parts(ptr, 0, len.max(1) as usize));
        }
    }

    /// Reads and frees a `[u32 len][json]` buffer.
    ///
    /// # Safety
    /// `ptr` must be a framed buffer allocated with [`alloc`].
    pub unsafe fn take_framed(ptr: *mut u8) -> Result<Value, String> {
        let n = u32::from_le_bytes(*(ptr as *const [u8; 4])) as usize;
        let v = serde_json::from_slice(core::slice::from_raw_parts(ptr.add(4), n)).map_err(|e| e.to_string());
        free(ptr, 4 + n as u32);
        v
    }

    /// Reads and frees a bare `(ptr, len)` JSON message from the host.
    ///
    /// # Safety
    /// `ptr` must address `len` bytes allocated with [`alloc`].
    pub unsafe fn take_message(ptr: *mut u8, len: u32) -> Value {
        let v = serde_json::from_slice(core::slice::from_raw_parts(ptr, len as usize)).unwrap_or(Value::Null);
        free(ptr, len);
        v
    }

    /// A framed buffer the host reads and frees.
    pub fn frame(v: &Value) -> *mut u8 {
        let b = serde_json::to_vec(v).unwrap_or_else(|_| b"{}".to_vec());
        let p = alloc(4 + b.len() as u32);
        unsafe {
            p.copy_from((b.len() as u32).to_le_bytes().as_ptr(), 4);
            p.add(4).copy_from(b.as_ptr(), b.len());
        }
        p
    }

    fn answer(id: &Value, r: Result<Value, HostError>) -> *mut u8 {
        match r {
            Ok(result) => frame(&json!({ "v": super::ABI_VERSION, "id": id, "ok": true, "result": result })),
            Err(e) => frame(&json!({ "v": super::ABI_VERSION, "id": id, "ok": false, "error": { "code": e.code, "message": e.message } })),
        }
    }

    pub fn activate<E: Extension>(ext: &mut E, info: &Value) -> *mut u8 {
        match ext.activate(info) {
            Ok(()) => frame(&json!({ "v": super::ABI_VERSION, "ok": true })),
            Err(m) => frame(&json!({ "v": super::ABI_VERSION, "ok": false, "error": { "code": "E_INTERNAL", "message": m } })),
        }
    }

    /// Dispatches one `ext_handle` message; null = no response (events, deactivate).
    pub fn handle<E: Extension>(ext: &mut E, msg: &Value) -> *mut u8 {
        let empty = Value::Null;
        match msg["type"].as_str() {
            Some("command") => {
                let command = msg["command"].as_str().unwrap_or("");
                answer(&msg["id"], ext.command(command, msg.get("args").unwrap_or(&empty)))
            }
            Some("request") => {
                let method = msg["method"].as_str().unwrap_or("");
                answer(&msg["id"], ext.request(method, msg.get("params").unwrap_or(&empty)))
            }
            Some("event") => {
                ext.event(msg["event"].as_str().unwrap_or(""), msg.get("data").unwrap_or(&empty));
                core::ptr::null_mut()
            }
            Some("deactivate") => {
                ext.deactivate();
                core::ptr::null_mut()
            }
            _ => core::ptr::null_mut(),
        }
    }
}

/// Exports the ABI v1 entry points for an [`Extension`] type that implements `Default`.
/// The exports exist only on wasm32: natively, `alloc`/`free` would replace the C allocator
/// and native unit tests of the guest's own logic would crash.
#[macro_export]
macro_rules! export_extension {
    ($ty:ty) => {
        #[allow(dead_code)]
        static mut __EASYIDE_EXT: Option<$ty> = None;

        #[allow(static_mut_refs, dead_code)]
        fn __easyide_ext() -> &'static mut $ty {
            // One instance, one worker thread per extension (sdk-reference): no concurrent access.
            unsafe { __EASYIDE_EXT.get_or_insert_with(<$ty as ::core::default::Default>::default) }
        }

        #[cfg(target_arch = "wasm32")]
        #[no_mangle]
        pub extern "C" fn ext_abi_version() -> i32 { $crate::ABI_VERSION }

        #[cfg(target_arch = "wasm32")]
        #[no_mangle]
        pub extern "C" fn alloc(len: u32) -> *mut u8 { $crate::abi::alloc(len) }

        #[cfg(target_arch = "wasm32")]
        #[no_mangle]
        pub unsafe extern "C" fn free(ptr: *mut u8, len: u32) { $crate::abi::free(ptr, len) }

        #[cfg(target_arch = "wasm32")]
        #[no_mangle]
        pub unsafe extern "C" fn ext_activate(ptr: *mut u8, len: u32) -> *mut u8 {
            let info = $crate::abi::take_message(ptr, len);
            $crate::abi::activate(__easyide_ext(), &info)
        }

        #[cfg(target_arch = "wasm32")]
        #[no_mangle]
        pub unsafe extern "C" fn ext_handle(ptr: *mut u8, len: u32) -> *mut u8 {
            let msg = $crate::abi::take_message(ptr, len);
            $crate::abi::handle(__easyide_ext(), &msg)
        }
    };
}

#[cfg(test)]
mod tests {
    use super::*;

    #[derive(Default)]
    struct Echo;
    impl Extension for Echo {
        fn command(&mut self, command: &str, args: &Value) -> Result<Value, HostError> {
            if command == "echo" { Ok(args.clone()) } else { Err(HostError::not_found(command)) }
        }
    }

    unsafe fn read(p: *mut u8) -> Value { abi::take_framed(p).unwrap() }

    #[test]
    fn commands_answer_with_their_id() {
        let mut e = Echo;
        let ok = unsafe { read(abi::handle(&mut e, &json!({"v":1,"type":"command","id":3,"command":"echo","args":[1]}))) };
        assert_eq!(ok, json!({"v":1,"id":3,"ok":true,"result":[1]}));
        let err = unsafe { read(abi::handle(&mut e, &json!({"v":1,"type":"command","id":4,"command":"nope"}))) };
        assert_eq!(err["ok"], json!(false));
        assert_eq!(err["error"]["code"], json!("E_NOT_FOUND"));
    }

    #[test]
    fn events_and_deactivate_have_no_response() {
        let mut e = Echo;
        assert!(abi::handle(&mut e, &json!({"v":1,"type":"event","event":"workspace.didSave","data":{}})).is_null());
        assert!(abi::handle(&mut e, &json!({"v":1,"type":"deactivate"})).is_null());
    }

    #[test]
    fn framing_round_trips() {
        let v = json!({"a":"ü","b":[1,2]});
        assert_eq!(unsafe { read(abi::frame(&v)) }, v);
    }
}
