import { verifyBackup } from './lib/backup-manifest.mjs';
if (process.argv.length !== 3 || process.argv[2] === '--help') {
  console.log('Read-only: node scripts/check-backup.mjs <backup-directory>. Verifies exact inventory, paths, sizes and SHA-256; never restores data.');
  process.exit(process.argv[2] === '--help' ? 0 : 1);
}
try { console.log(JSON.stringify({ status: 'PASS', ...await verifyBackup(process.argv[2]) }, null, 2)); }
catch (error) { console.error(`FAIL: ${error.message}`); process.exitCode = 1; }
