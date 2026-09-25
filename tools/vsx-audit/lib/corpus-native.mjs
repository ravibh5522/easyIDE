// corpus-native.mjs - detect native binaries (ELF / Mach-O / PE / wasm) inside an unpacked extension.
// ELF: e_machine -> arch; PT_INTERP -> libc (ld-linux* = glibc, ld-musl* = musl); for objects without PT_INTERP
// (.node addons, .so) the libc is inferred from strings: "GLIBC_2." => glibc, "musl" loader/libc name => musl,
// neither => "static/none". glibcMax = highest GLIBC_2.x symbol version referenced (the minimum glibc needed).
import fs from 'node:fs';

const MACHINES = { 0x03: 'x86', 0x28: 'arm', 0x3e: 'x86-64', 0xb7: 'aarch64', 0xf3: 'riscv', 0x08: 'mips' };

function readHead(file, n) {
  const fd = fs.openSync(file, 'r');
  try {
    const b = Buffer.alloc(n);
    const r = fs.readSync(fd, b, 0, n, 0);
    return b.subarray(0, r);
  } finally { fs.closeSync(fd); }
}

function cmpVer(a, b) {
  const x = a.split('.').map(Number), y = b.split('.').map(Number);
  for (let i = 0; i < Math.max(x.length, y.length); i++) if ((x[i] || 0) !== (y[i] || 0)) return (x[i] || 0) - (y[i] || 0);
  return 0;
}

function elfInfo(file, size) {
  const h = readHead(file, 64);
  const is64 = h[4] === 2;
  const le = h[5] === 1;
  const u16 = (b, o) => (le ? b.readUInt16LE(o) : b.readUInt16BE(o));
  const u32 = (b, o) => (le ? b.readUInt32LE(o) : b.readUInt32BE(o));
  const u64 = (b, o) => Number(le ? b.readBigUInt64LE(o) : b.readBigUInt64BE(o));
  const eType = u16(h, 16);
  const arch = MACHINES[u16(h, 18)] || `machine-0x${u16(h, 18).toString(16)}`;
  const phoff = is64 ? u64(h, 32) : u32(h, 28);
  const phentsize = u16(h, is64 ? 54 : 42);
  const phnum = u16(h, is64 ? 56 : 44);
  let interp = null;
  const all = fs.readFileSync(file);
  for (let i = 0; i < phnum; i++) {
    const o = phoff + i * phentsize;
    if (o + phentsize > all.length) break;
    if (u32(all, o) === 3) { // PT_INTERP
      const off = is64 ? u64(all, o + 8) : u32(all, o + 4);
      const sz = is64 ? u64(all, o + 32) : u32(all, o + 16);
      interp = all.subarray(off, off + sz).toString('latin1').replace(/\0.*$/, '');
    }
  }
  let libc = null;
  if (interp) libc = /musl/.test(interp) ? 'musl' : /ld-linux|ld64|ld\.so/.test(interp) ? 'glibc' : 'other';
  const text = all.toString('latin1');
  let glibcMax = null;
  for (const m of text.matchAll(/GLIBC_(2\.\d+(?:\.\d+)?)/g)) if (!glibcMax || cmpVer(m[1], glibcMax) > 0) glibcMax = m[1];
  if (!libc) libc = /ld-musl|libc\.musl/.test(text) ? 'musl' : glibcMax ? 'glibc' : 'static/none';
  return { format: 'elf', elfType: { 1: 'rel', 2: 'exec', 3: 'dyn' }[eType] || String(eType), arch, libc, interp, glibcMax, bytes: size };
}

/** Classify a file; returns null when it is not a native binary. */
export function nativeInfo(file, rel, size) {
  if (size < 64) return null;
  const h = readHead(file, 8);
  const lower = rel.toLowerCase();
  if (h[0] === 0x7f && h[1] === 0x45 && h[2] === 0x4c && h[3] === 0x46) {
    const e = elfInfo(file, size);
    const kind = lower.endsWith('.node') ? 'node-addon' : e.elfType === 'exec' || (e.elfType === 'dyn' && e.interp) ? 'elf-exec' : 'other';
    return { path: rel, kind, ...e };
  }
  const m = h.readUInt32BE(0);
  if ([0xfeedface, 0xfeedfacf, 0xcefaedfe, 0xcffaedfe].includes(m) || (m === 0xcafebabe && !lower.endsWith('.class'))) {
    return { path: rel, kind: lower.endsWith('.node') ? 'node-addon' : 'other', format: 'macho', arch: null, libc: null, bytes: size };
  }
  if (h[0] === 0x4d && h[1] === 0x5a && /\.(exe|dll|node|pyd)$/.test(lower)) {
    return { path: rel, kind: lower.endsWith('.node') ? 'node-addon' : 'other', format: 'pe', arch: null, libc: null, bytes: size };
  }
  if (h.readUInt32BE(0) === 0x0061736d) return { path: rel, kind: 'wasm', format: 'wasm', arch: 'wasm32', libc: null, bytes: size };
  return null;
}
