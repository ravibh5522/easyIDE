;; Imports WASI: load refused (R-SEC-10).
(module
  (import "wasi_snapshot_preview1" "fd_write" (func (param i32 i32 i32 i32) (result i32)))
  (memory (export "memory") 1 16)
  (func (export "alloc") (param i32) (result i32) (i32.const 1024))
  (func (export "free") (param i32 i32))
  (func (export "ext_activate") (param i32 i32) (result i32) (i32.const 0))
  (func (export "ext_handle") (param i32 i32) (result i32) (i32.const 0))
  (func (export "ext_abi_version") (result i32) (i32.const 1)))
