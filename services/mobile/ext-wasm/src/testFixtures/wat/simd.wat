;; Uses SIMD: the metering pass refuses it by name.
(module
  (memory (export "memory") 1 16)
  (func (export "alloc") (param i32) (result i32) (i32.const 1024))
  (func (export "free") (param i32 i32))
  (func (export "ext_activate") (param i32 i32) (result i32) (i32.const 0))
  (func (export "ext_handle") (param i32 i32) (result i32)
    (drop (v128.const i32x4 0 0 0 0)) (i32.const 0))
  (func (export "ext_abi_version") (result i32) (i32.const 1)))
