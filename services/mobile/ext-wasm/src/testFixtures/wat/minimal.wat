;; Smallest valid ABI v1 guest: activation answers ok, every ext_handle answers "no response".
;; Measures pure host overhead per call (no guest work).
(module
  (import "easyide" "host_call" (func (param i32 i32) (result i32)))
  (memory (export "memory") 1 1)
  ;; [u32 LE 17]{"v":1,"ok":true}
  (data (i32.const 16) "\11\00\00\00{\"v\":1,\"ok\":true}")
  (func (export "alloc") (param i32) (result i32) (i32.const 1024))
  (func (export "free") (param i32 i32))
  (func (export "ext_abi_version") (result i32) (i32.const 1))
  (func (export "ext_activate") (param i32 i32) (result i32) (i32.const 16))
  (func (export "ext_handle") (param i32 i32) (result i32) (i32.const 0)))
