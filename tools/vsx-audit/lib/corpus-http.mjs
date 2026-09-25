// corpus-http.mjs - cached, rate-limited HTTP helpers for the Open VSX corpus audit.
//
// - Every JSON (and small text) response is cached under $CACHE/api/<sanitised-url>, so re-runs are offline.
// - At most N concurrent requests (default 4 for API, 3 for downloads), retry with exponential backoff on 429/5xx/network errors.
// - Node's built-in fetch only honours HTTPS_PROXY when NODE_USE_ENV_PROXY=1 (Node >= 22.21); ensureProxyEnv() re-execs.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';

export const CACHE = process.env.CORPUS_CACHE || '/root/.cache/easyide-corpus';
export const API_DIR = path.join(CACHE, 'api');
export const VSIX_DIR = path.join(CACHE, 'vsix');
export const X_DIR = path.join(CACHE, 'x');
export const OFFLINE = process.env.CORPUS_OFFLINE === '1';

/** Re-exec the current script with NODE_USE_ENV_PROXY=1 when a proxy is configured and fetch would ignore it. */
export function ensureProxyEnv() {
  if ((process.env.HTTPS_PROXY || process.env.https_proxy) && process.env.NODE_USE_ENV_PROXY !== '1') {
    const r = spawnSync(process.execPath, ['--no-warnings', ...process.argv.slice(1)], {
      stdio: 'inherit',
      env: { ...process.env, NODE_USE_ENV_PROXY: '1' },
    });
    process.exit(r.status ?? 1);
  }
}

export function limiter(n) {
  let active = 0;
  const q = [];
  const next = () => {
    if (active >= n || !q.length) return;
    active++;
    const { fn, res, rej } = q.shift();
    Promise.resolve()
      .then(fn)
      .then(res, rej)
      .finally(() => {
        active--;
        next();
      });
  };
  return (fn) => new Promise((res, rej) => { q.push({ fn, res, rej }); next(); });
}

const apiLimit = limiter(Number(process.env.CORPUS_API_CONCURRENCY || 4));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

export function cachePathFor(url) {
  const u = new URL(url);
  let p = (u.pathname + (u.search ? '_' + u.search.slice(1) : '')).replace(/^\/+/, '');
  p = p.replace(/[^A-Za-z0-9._\-\/@]/g, '_');
  if (p.length > 200) p = p.slice(0, 160) + '_' + crypto.createHash('sha1').update(url).digest('hex');
  return path.join(API_DIR, u.host, p + (p.endsWith('.json') ? '' : '.cache'));
}

async function fetchRetry(url, opts = {}, tries = 6) {
  let delay = 1000;
  for (let i = 0; ; i++) {
    try {
      const r = await fetch(url, { redirect: 'follow', ...opts });
      if (r.status === 429 || r.status >= 500) {
        if (i >= tries - 1) return r;
        const ra = Number(r.headers.get('retry-after'));
        await sleep(ra > 0 ? ra * 1000 : delay);
        delay *= 2;
        continue;
      }
      return r;
    } catch (e) {
      if (i >= tries - 1) throw e;
      await sleep(delay);
      delay *= 2;
    }
  }
}

/** GET with on-disk cache. Returns {status, body(text), cached}. 404s are cached too (as status 404). */
export async function getCached(url) {
  const cp = cachePathFor(url);
  if (fs.existsSync(cp)) {
    const raw = fs.readFileSync(cp, 'utf8');
    const nl = raw.indexOf('\n');
    const status = Number(raw.slice(0, nl));
    return { status, body: raw.slice(nl + 1), cached: true };
  }
  if (OFFLINE) return { status: 0, body: '', cached: false };
  return apiLimit(async () => {
    const r = await fetchRetry(url);
    const body = await r.text();
    if (r.status === 200 || r.status === 404) {
      fs.mkdirSync(path.dirname(cp), { recursive: true });
      fs.writeFileSync(cp, r.status + '\n' + body);
    }
    return { status: r.status, body, cached: false };
  });
}

export async function getJson(url) {
  const r = await getCached(url);
  if (r.status !== 200) return null;
  try { return JSON.parse(r.body); } catch { return null; }
}

/** Stream a (large) file to disk; returns {bytes, sha256}. Skips download when dest exists. */
export async function download(url, dest) {
  if (fs.existsSync(dest)) {
    return { bytes: fs.statSync(dest).size, sha256: await sha256File(dest), cached: true };
  }
  if (OFFLINE) throw new Error('offline and not cached: ' + url);
  const tmp = dest + '.part';
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  const r = await fetchRetry(url);
  if (r.status !== 200) throw new Error(`HTTP ${r.status} for ${url}`);
  const h = crypto.createHash('sha256');
  let bytes = 0;
  const src = Readable.fromWeb(r.body);
  src.on('data', (c) => { h.update(c); bytes += c.length; });
  await pipeline(src, fs.createWriteStream(tmp));
  fs.renameSync(tmp, dest);
  return { bytes, sha256: h.digest('hex'), cached: false };
}

export function sha256File(p) {
  return new Promise((res, rej) => {
    const h = crypto.createHash('sha256');
    fs.createReadStream(p).on('data', (c) => h.update(c)).on('end', () => res(h.digest('hex'))).on('error', rej);
  });
}
