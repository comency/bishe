import { readFile } from 'node:fs/promises';
import { createManifest, verifyManifest } from './lib/release-manifest.mjs';
if (process.argv.length !== 5 || process.argv[2] !== '--confirm-local-candidate') throw new Error('Explicit --confirm-local-candidate, output directory and build metadata file required');
const metadata = JSON.parse((await readFile(process.argv[4], 'utf8')).replace(/^\uFEFF/, ''));
await createManifest(process.argv[3], metadata);
console.log(JSON.stringify({ status: 'PASS', ...await verifyManifest(process.argv[3]) }));
