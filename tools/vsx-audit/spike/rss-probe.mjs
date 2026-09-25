// Loads the ext host bundle and answers 'rss' IPC requests with memory numbers.
import fs from 'node:fs';
import { pathToFileURL } from 'node:url';
const tStart = Date.now();
process.on('message', (m) => {
  if (m !== 'rss') return;
  const mu = process.memoryUsage();
  const st = fs.readFileSync('/proc/self/status', 'utf8');
  const kb = (k) => Number((st.match(new RegExp(`${k}:\\s+(\\d+)`)) || [])[1]);
  process.send({ rss: Math.round(mu.rss / 1048576), heapUsed: Math.round(mu.heapUsed / 1048576), heapTotal: Math.round(mu.heapTotal / 1048576), external: Math.round(mu.external / 1048576), VmHWM_MB: Math.round(kb('VmHWM') / 1024), activatedMsAfterProcessStart: globalThis.__spikeActivated ? globalThis.__spikeActivated - tStart : null, importMs: globalThis.__spikeImportMs });
});
const t = Date.now();
await import(pathToFileURL(process.argv[2]).href);
globalThis.__spikeImportMs = Date.now() - t;
import('node:module').then(m => setTimeout(() => m.flushCompileCache?.(), 1200));
