;; Control-flow and feature coverage for the metering pass: every export must return the same
;; value before and after instrumentation. No globals and no imports on purpose, so the pass
;; has to create the global section (proxy.wat covers appending to an existing one).
(module
  (memory (export "memory") 1 2)
  (table 2 funcref)
  (elem (i32.const 0) $double $square)
  (type $unary (func (param i32) (result i32)))

  (func $double (type $unary) (i32.mul (local.get 0) (i32.const 2)))
  (func $square (type $unary) (i32.mul (local.get 0) (local.get 0)))

  (func (export "fib") (param $n i32) (result i64)
    (local $a i64) (local $b i64) (local $t i64)
    (local.set $b (i64.const 1))
    (block $done
      (loop $l
        (br_if $done (i32.eqz (local.get $n)))
        (local.set $t (i64.add (local.get $a) (local.get $b)))
        (local.set $a (local.get $b))
        (local.set $b (local.get $t))
        (local.set $n (i32.sub (local.get $n) (i32.const 1)))
        (br $l)))
    (local.get $a))

  (func (export "nested") (param $n i32) (result i32)
    (local $i i32) (local $j i32) (local $s i32)
    (loop $outer
      (local.set $j (i32.const 0))
      (loop $inner
        (local.set $s (i32.add (local.get $s) (i32.mul (local.get $i) (local.get $j))))
        (local.set $j (i32.add (local.get $j) (i32.const 1)))
        (br_if $inner (i32.lt_s (local.get $j) (local.get $n))))
      (local.set $i (i32.add (local.get $i) (i32.const 1)))
      (br_if $outer (i32.lt_s (local.get $i) (local.get $n))))
    (local.get $s))

  (func (export "switch") (param $x i32) (result i32)
    (block $c (block $b (block $a
      (br_table $a $b $c (local.get $x)))
      (return (i32.const 10)))
      (return (i32.const 20)))
    (i32.const 30))

  (func (export "indirect") (param $which i32) (param $v i32) (result i32)
    (call_indirect (type $unary) (local.get $v) (local.get $which)))

  (func (export "early") (param $n i32) (result i32)
    (local $i i32)
    (loop $l
      (if (i32.eq (local.get $i) (local.get $n)) (then (return (i32.mul (local.get $i) (i32.const 3)))))
      (local.set $i (i32.add (local.get $i) (i32.const 1)))
      (br $l))
    (i32.const -1))

  (func $pair (param $x i32) (result i32 i32) (local.get $x) (i32.add (local.get $x) (i32.const 1)))
  (func (export "multi") (param $x i32) (result i32)
    (block (result i32 i32) (call $pair (local.get $x)))
    (i32.mul)
    (if (result i32) (i32.gt_s (local.get $x) (i32.const 5))
      (then (i32.const 1))
      (else (i32.const 0)))
    (i32.add))

  (func (export "features") (param $x i32) (result i32)
    (memory.fill (i32.const 0) (local.get $x) (i32.const 16))
    (memory.copy (i32.const 32) (i32.const 0) (i32.const 16))
    (i32.add
      (i32.add (i32.load8_u (i32.const 40)) (i32.extend8_s (local.get $x)))
      (i32.add
        (i32.trunc_sat_f64_s (f64.const 1e30))
        (select (i32.const 7) (i32.const 9) (ref.is_null (table.get (i32.const 1)))))))

  (func (export "spin") (loop $l (br_if $l (i32.const 1))))
)
