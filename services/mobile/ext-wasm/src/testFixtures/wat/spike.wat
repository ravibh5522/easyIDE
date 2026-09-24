;; ART spike fixture for decision 0014: re-entrant alloc from host_call, memory cap,
;; interruption and the checkpoint shape the metering pass injects (wasm-host.md sec 4),
;; written out by hand so the spike does not depend on the pass under test.
(module
  (import "easyide" "host_call" (func $host_call (param i32 i32) (result i32)))
  (memory (export "memory") 1 1024)
  (global $heap (mut i32) (i32.const 1024))
  ;; Stand-in for the injected __easyide_fuel global (last global, never exported).
  (global $fuel (mut i64) (i64.const 0))

  ;; Bump allocator that grows memory on demand; free is a no-op.
  (func $alloc (export "alloc") (param $n i32) (result i32)
    (local $p i32) (local $end i32) (local $size i32)
    (local.set $p (global.get $heap))
    (local.set $end (i32.add (local.get $p) (local.get $n)))
    (local.set $size (i32.mul (memory.size) (i32.const 65536)))
    (if (i32.gt_u (local.get $end) (local.get $size))
      (then
        (if (i32.eq (i32.const -1)
              (memory.grow (i32.add (i32.const 1)
                (i32.shr_u (i32.sub (local.get $end) (local.get $size)) (i32.const 16)))))
          (then unreachable))))
    (global.set $heap (local.get $end))
    (local.get $p))
  (func (export "free") (param i32 i32))
  (func (export "ext_abi_version") (result i32) (i32.const 1))

  ;; Forwards a guest buffer to host_call and returns what the host allocated.
  (func (export "echo") (param $p i32) (param $n i32) (result i32)
    (call $host_call (local.get $p) (local.get $n)))

  (func $recurse (export "recurse") (param $n i32) (result i32)
    (if (result i32) (i32.eqz (local.get $n))
      (then (i32.const 0))
      (else (i32.add (i32.const 1) (call $recurse (i32.sub (local.get $n) (i32.const 1)))))))

  (func (export "grow") (param $pages i32) (result i32)
    (memory.grow (local.get $pages)))

  ;; n iterations of an 8-instruction body, no checkpoints: raw interpreter speed.
  (func (export "count") (param $n i64) (result i64)
    (local $i i64)
    (loop $l
      (local.set $i (i64.add (local.get $i) (i64.const 1)))
      (br_if $l (i64.lt_u (local.get $i) (local.get $n))))
    (local.get $i))

  ;; Same loop with the metering checkpoint at the loop header.
  (func (export "count_metered") (param $n i64) (result i64)
    (local $i i64)
    (loop $l
      (global.set $fuel (i64.sub (global.get $fuel) (i64.const 16)))
      (if (i64.lt_s (global.get $fuel) (i64.const 0)) (then unreachable))
      (local.set $i (i64.add (local.get $i) (i64.const 1)))
      (br_if $l (i64.lt_u (local.get $i) (local.get $n))))
    (local.get $i))

  ;; Endless br_if loop: Chicory only polls Thread.interrupt on call and br.
  (func (export "spin_br_if")
    (loop $l (br_if $l (i32.const 1))))

  ;; Endless br loop: Chicory polls Thread.interrupt here.
  (func (export "spin_br")
    (loop $l (br $l)))

  ;; Endless loop that only a fuel write from another thread can stop.
  (func (export "spin_metered")
    (loop $l
      (global.set $fuel (i64.sub (global.get $fuel) (i64.const 3)))
      (if (i64.lt_s (global.get $fuel) (i64.const 0)) (then unreachable))
      (br_if $l (i32.const 1))))
)
