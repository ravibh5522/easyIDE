;; Generic ABI v1 test guest ("proxy"). Commands select a scenario by name; `test.proxy`
;; forwards its single argument as a host_call request and answers with the host's reply as
;; the result, so JVM and device tests can drive any host function through WasmHost.
;; Generated layout: data offsets/lengths below are fixed; edit with care.
(module
  ;; #include guest.wati
  (memory (export "memory") 1 1024)
  (global $slow_deactivate (mut i32) (i32.const 0))
  (data (i32.const 16) "\"id\":") ;; ID_KEY
  (data (i32.const 21) "\"args\":[") ;; ARGS_KEY
  (data (i32.const 29) "],\"context\":") ;; CTX_KEY
  (data (i32.const 41) "\"type\":\"event\"") ;; T_EVENT
  (data (i32.const 55) "\"type\":\"request\"") ;; T_REQUEST
  (data (i32.const 71) "\"type\":\"deactivate\"") ;; T_DEACT
  (data (i32.const 90) "\"test.proxy\"") ;; C_PROXY
  (data (i32.const 102) "\"test.spin\"") ;; C_SPIN
  (data (i32.const 113) "\"test.trap\"") ;; C_TRAP
  (data (i32.const 124) "\"test.grow\"") ;; C_GROW
  (data (i32.const 135) "\"test.storm\"") ;; C_STORM
  (data (i32.const 147) "\"test.badReply\"") ;; C_BADREPLY
  (data (i32.const 162) "\"test.badJson\"") ;; C_BADJSON
  (data (i32.const 176) "\"test.noReply\"") ;; C_NOREPLY
  (data (i32.const 190) "\"test.recurse\"") ;; C_RECURSE
  (data (i32.const 204) "\"test.frees\"") ;; C_FREES
  (data (i32.const 216) "\"test.slowDeactivate\"") ;; C_SLOWDEACT
  (data (i32.const 237) "\"test.badRequest\"") ;; C_BADREQ
  (data (i32.const 254) "\"test.oobRequest\"") ;; C_OOBREQ
  (data (i32.const 271) "{\"v\":1,\"id\":") ;; REPLY_HEAD
  (data (i32.const 283) ",\"ok\":true,\"result\":") ;; REPLY_MID
  (data (i32.const 303) "}") ;; CLOSE
  (data (i32.const 304) "}}") ;; CLOSE2
  (data (i32.const 306) "null") ;; NULL
  (data (i32.const 310) "{\"v\":1,\"ok\":true}") ;; ACT_OK
  (data (i32.const 327) "{\"v\":1,\"ok\":false,\"error\":{\"code\":\"E_ARGS\",\"message\":\"refused by guest\"}}") ;; ACT_FAIL
  (data (i32.const 400) "\"failActivation\"") ;; M_FAILACT
  (data (i32.const 416) "\"trapActivation\"") ;; M_TRAPACT
  (data (i32.const 432) "{\"v\":1,\"id\":0,\"fn\":\"storage.set\",\"args\":{\"key\":\"event\",\"value\":") ;; STORE_EVENT
  (data (i32.const 495) "{\"v\":1,\"id\":0,\"fn\":\"storage.set\",\"args\":{\"key\":\"activation\",\"value\":") ;; STORE_ACT
  (data (i32.const 563) "{\"v\":1,\"id\":1,\"fn\":\"commands.register\",\"args\":{\"command\":\"test.proxy\"}}") ;; REG_CMD
  (data (i32.const 634) "{\"v\":1,\"id\":2,\"fn\":\"events.subscribe\",\"args\":{\"names\":[\"workspace.didSave\",\"workspace.didChange\"]}}") ;; SUBSCRIBE
  (data (i32.const 733) "{\"v\":1,\"id\":3,\"fn\":\"providers.register\",\"args\":{\"kind\":\"completion\",\"languages\":[\"python\"]}}") ;; REG_PROV
  (data (i32.const 825) "{\"v\":1,\"id\":4,\"fn\":\"host.info\",\"args\":{}}") ;; INFO
  (data (i32.const 866) "[{\"label\":\"wasm\"}]") ;; PROVIDER_RESULT
  (data (i32.const 884) "-1") ;; GROW_FAIL
  (data (i32.const 886) "1") ;; GROW_OK
  (data (i32.const 887) "{\"v\":1,\"id\":5,\"fn\":") ;; BAD_JSON_REQ
  (data (i32.const 906) "{nope") ;; BAD_JSON_REPLY

  (func (export "ext_abi_version") (result i32) (i32.const 1))

  ;; host_call a data-segment request; frees the reply as the receiver must.
  (func $call (param $p i32) (param $n i32)
    (local $r i32)
    (local.set $r (call $host_call (local.get $p) (local.get $n)))
    (call $free (local.get $r) (i32.add (i32.const 4) (i32.load (local.get $r)))))

  (func (export "ext_activate") (param $p i32) (param $n i32) (result i32)
    (if (call $has (local.get $p) (local.get $n) (i32.const 416) (i32.const 16)) (then unreachable))
    (call $call
      (call $build (i32.const 0) (i32.const 495) (i32.const 68) (local.get $p) (local.get $n) (i32.const 304) (i32.const 2))
      (i32.add (local.get $n) (i32.const 70)))
    (call $call (i32.const 563) (i32.const 71))
    (call $call (i32.const 634) (i32.const 99))
    (call $call (i32.const 733) (i32.const 92))
    (call $free (local.get $p) (local.get $n))
    (if (result i32) (call $has (local.get $p) (local.get $n) (i32.const 400) (i32.const 16))
      (then (call $build (i32.const 1) (i32.const 327) (i32.const 73) (i32.const 0) (i32.const 0) (i32.const 0) (i32.const 0)))
      (else (call $build (i32.const 1) (i32.const 310) (i32.const 17) (i32.const 0) (i32.const 0) (i32.const 0) (i32.const 0)))))

  ;; [len]{"v":1,"id":ID,"ok":true,"result":R}
  (func $reply (param $id i32) (param $idl i32) (param $r i32) (param $rl i32) (result i32)
    (call $build (i32.const 1)
      (call $build (i32.const 0) (i32.const 271) (i32.const 12) (local.get $id) (local.get $idl) (i32.const 283) (i32.const 20))
      (i32.add (local.get $idl) (i32.const 32))
      (local.get $r) (local.get $rl) (i32.const 303) (i32.const 1)))

  (func $recurse (param $d i32) (result i32)
    (if (result i32) (i32.eqz (local.get $d))
      (then (i32.const 0))
      (else (i32.add (i32.const 1) (call $recurse (i32.sub (local.get $d) (i32.const 1)))))))

  (func (export "ext_handle") (param $p i32) (param $n i32) (result i32)
    (local $id i32) (local $idl i32) (local $a i32) (local $b i32) (local $r i32) (local $i i32)
    (if (call $has (local.get $p) (local.get $n) (i32.const 71) (i32.const 19))
      (then
        (call $free (local.get $p) (local.get $n))
        (if (global.get $slow_deactivate) (then (loop $forever (br_if $forever (i32.const 1)))))
        (return (i32.const 0))))
    (if (call $has (local.get $p) (local.get $n) (i32.const 41) (i32.const 14))
      (then
        (call $call
          (call $build (i32.const 0) (i32.const 432) (i32.const 63) (local.get $p) (local.get $n) (i32.const 304) (i32.const 2))
          (i32.add (local.get $n) (i32.const 65)))
        (call $free (local.get $p) (local.get $n))
        (return (i32.const 0))))
    ;; The first "id": is the message id (the host writes it before args/params).
    (local.set $id (i32.add (local.get $p)
      (i32.add (call $find (local.get $p) (local.get $n) (i32.const 16) (i32.const 5)) (i32.const 5))))
    (block $digits
      (loop $scan
        (br_if $digits (i32.gt_u (i32.sub (i32.load8_u (i32.add (local.get $id) (local.get $idl))) (i32.const 48)) (i32.const 9)))
        (local.set $idl (i32.add (local.get $idl) (i32.const 1)))
        (br $scan)))
    (if (call $has (local.get $p) (local.get $n) (i32.const 55) (i32.const 16))
      (then (return (call $reply (local.get $id) (local.get $idl) (i32.const 866) (i32.const 18)))))
    (if (call $has (local.get $p) (local.get $n) (i32.const 102) (i32.const 11)) (then (loop $spin (br_if $spin (i32.const 1))) (unreachable)))
    (if (call $has (local.get $p) (local.get $n) (i32.const 113) (i32.const 11)) (then unreachable))
    (if (call $has (local.get $p) (local.get $n) (i32.const 176) (i32.const 14)) (then (return (i32.const 0))))
    (if (call $has (local.get $p) (local.get $n) (i32.const 124) (i32.const 11))
      (then
        (if (i32.eq (memory.grow (i32.const 40)) (i32.const -1))
          (then (return (call $reply (local.get $id) (local.get $idl) (i32.const 884) (i32.const 2)))))
        (return (call $reply (local.get $id) (local.get $idl) (i32.const 886) (i32.const 1)))))
    (if (call $has (local.get $p) (local.get $n) (i32.const 135) (i32.const 12))
      (then
        (loop $storm
          (call $call (i32.const 825) (i32.const 41))
          (local.set $i (i32.add (local.get $i) (i32.const 1)))
          (br_if $storm (i32.lt_u (local.get $i) (i32.const 5000))))
        (return (call $reply (local.get $id) (local.get $idl) (i32.const 306) (i32.const 4)))))
    (if (call $has (local.get $p) (local.get $n) (i32.const 147) (i32.const 15))
      (then
        (local.set $r (call $alloc (i32.const 8)))
        (i32.store (local.get $r) (i32.const 0x7fffffff))
        (return (local.get $r))))
    (if (call $has (local.get $p) (local.get $n) (i32.const 162) (i32.const 14))
      (then (return (call $build (i32.const 1) (i32.const 906) (i32.const 5) (i32.const 0) (i32.const 0) (i32.const 0) (i32.const 0)))))
    (if (call $has (local.get $p) (local.get $n) (i32.const 190) (i32.const 14))
      (then (drop (call $recurse (i32.const 100000000)))))
    (if (call $has (local.get $p) (local.get $n) (i32.const 216) (i32.const 21)) (then (global.set $slow_deactivate (i32.const 1))))
    (if (call $has (local.get $p) (local.get $n) (i32.const 204) (i32.const 12))
      (then
        (local.set $r (call $itoa (global.get $frees)))
        (return (call $reply (local.get $id) (local.get $idl) (local.get $r) (global.get $itoa_len)))))
    (if (call $has (local.get $p) (local.get $n) (i32.const 237) (i32.const 17))
      (then (local.set $r (call $host_call (i32.const 887) (i32.const 19)))))
    (if (call $has (local.get $p) (local.get $n) (i32.const 254) (i32.const 17))
      (then (local.set $r (call $host_call (i32.const 0x7ffffff0) (i32.const 100)))))
    (if (call $has (local.get $p) (local.get $n) (i32.const 90) (i32.const 12))
      (then
        (local.set $a (i32.add (call $find (local.get $p) (local.get $n) (i32.const 21) (i32.const 8)) (i32.const 8)))
        (local.set $b (call $find (local.get $p) (local.get $n) (i32.const 29) (i32.const 12)))
        (local.set $r (call $host_call (i32.add (local.get $p) (local.get $a)) (i32.sub (local.get $b) (local.get $a))))))
    (if (local.get $r)
      (then
        (call $free (local.get $p) (local.get $n))
        (call $free (local.get $r) (i32.add (i32.const 4) (i32.load (local.get $r))))
        (return (call $reply (local.get $id) (local.get $idl) (i32.add (local.get $r) (i32.const 4)) (i32.load (local.get $r))))))
    (call $free (local.get $p) (local.get $n))
    (call $reply (local.get $id) (local.get $idl) (i32.const 306) (i32.const 4)))
)
