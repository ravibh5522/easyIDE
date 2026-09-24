;; Exports a name with the reserved __easyide_ prefix: load refused.
(module
  (memory (export "memory") 1 16)
  (func (export "alloc") (param i32) (result i32) (i32.const 1024))
  (func (export "free") (param i32 i32))
  (func (export "ext_activate") (param i32 i32) (result i32) (i32.const 0))
  (global (export "__easyide_fuel") i32 (i32.const 0))
  (func (export "ext_handle") (param i32 i32) (result i32) (i32.const 0))
  (func (export "ext_abi_version") (result i32) (i32.const 1)))
