import { createRequire } from 'node:module';
const require = createRequire(process.env.SP + '/node_modules-spike/package.json');
const esbuild = require('esbuild');
await esbuild.build({ entryPoints: [process.env.SP + '/spike/renderer.ts'], outfile: process.env.SP + '/spike/out/renderer.mjs', bundle: true, format: 'esm', platform: 'node', target: ['es2024'], packages: 'external', logLevel: 'warning',
  tsconfigRaw: JSON.stringify({ compilerOptions: { experimentalDecorators: true, useDefineForClassFields: false } }) });
console.log('renderer built');
