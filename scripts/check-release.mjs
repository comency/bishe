import { verifyManifest } from './lib/release-manifest.mjs';
if (process.argv.length !== 3 || process.argv[2] === '--help') {
  console.log('Read-only: node scripts/check-release.mjs <candidate-directory>. Validates exact inventory and SHA-256; does not deploy or establish signature/provenance.');
  process.exit(process.argv[2] === '--help' ? 0 : 1);
}
try { console.log(JSON.stringify({ status: 'PASS', ...await verifyManifest(process.argv[2]) }, null, 2)); }
catch (error) { console.error(`FAIL: ${error.message}`); process.exitCode = 1; }
