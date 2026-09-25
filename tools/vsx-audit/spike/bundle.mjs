// Spike: bundle VS Code's node extension host with esbuild, mirroring build/next/bundle.ts options.
import { createRequire } from 'node:module';
import fs from 'node:fs'; import path from 'node:path'; import zlib from 'node:zlib';
const require = createRequire(process.env.SP + '/node_modules-spike/package.json');
const esbuild = require('esbuild');
const VS = process.env.SP + '/vscode';
const entry = process.argv[2] || 'vs/workbench/api/node/extensionHostProcess';
const outName = process.argv[3] || 'exthost';
const extra = JSON.parse(process.argv[4] || '{}');
for (const minify of [false, true]) {
  const outfile = `${process.env.SP}/spike/out/${outName}${minify ? '.min' : ''}.mjs`;
  const t = Date.now();
  const r = await esbuild.build({
    entryPoints: [`${VS}/src/${entry}.ts`], outfile, bundle: true, format: 'esm', platform: 'node', target: ['es2024'],
    packages: 'external', minify, treeShaking: true, metafile: true, logLevel: 'warning', logOverride: { 'unsupported-require-call': 'silent' },
    loader: { '.ttf': 'file', '.svg': 'file', '.png': 'file', '.sh': 'file' },
    tsconfigRaw: JSON.stringify({ compilerOptions: { experimentalDecorators: true, useDefineForClassFields: false } }),
    ...extra,
  });
  const buf = fs.readFileSync(outfile);
  const inputs = Object.keys(r.metafile.inputs);
  console.log(JSON.stringify({ outfile, minify, ms: Date.now() - t, bytes: buf.length, gzip: zlib.gzipSync(buf, { level: 9 }).length, brotli: zlib.brotliCompressSync(buf).length, inputs: inputs.length }));
  if (!minify) fs.writeFileSync(`${process.env.SP}/spike/out/${outName}.meta.json`, JSON.stringify(r.metafile));
}
