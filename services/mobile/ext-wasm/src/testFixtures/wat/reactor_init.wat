;; Reactor module: `_initialize` must run before ext_abi_version, which reports the global it sets.
(module
  (import "easyide" "host_call" (func (param i32 i32) (result i32)))
  (memory (export "memory") 1 1)
  (global $ready (mut i32) (i32.const 0))
  (data (i32.const 16) "\11\00\00\00{\"v\":1,\"ok\":true}")
  (func (export "_initialize") (global.set $ready (i32.const 1)))
  (func (export "alloc") (param i32) (result i32) (i32.const 1024))
  (func (export "free") (param i32 i32))
  (func (export "ext_abi_version") (result i32) (global.get $ready))
  (func (export "ext_activate") (param i32 i32) (result i32) (i32.const 16))
  (func (export "ext_handle") (param i32 i32) (result i32) (i32.const 0)))
