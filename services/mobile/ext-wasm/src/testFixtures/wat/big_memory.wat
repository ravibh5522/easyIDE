;; Needs 2048 initial pages (128 MB), over the 64 MB default cap: load refused.
(module
  (memory (export "memory") 2048)
  (func (export "alloc") (param i32) (result i32) (i32.const 1024))
  (func (export "free") (param i32 i32))
  (func (export "ext_activate") (param i32 i32) (result i32) (i32.const 0))
  (func (export "ext_handle") (param i32 i32) (result i32) (i32.const 0))
  (func (export "ext_abi_version") (result i32) (i32.const 1)))
