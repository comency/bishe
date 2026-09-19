import { verifyBackup } from './lib/backup-manifest.mjs';
const args = process.argv.slice(2);
if (args[0] === '--help' || (args.length !== 1 && args.length !== 3) || (args.length === 3 && args[1] !== '--max-age-hours')) {
  console.log('Read-only: node scripts/check-backup.mjs <backup-directory> [--max-age-hours <positive number>]. Verifies exact inventory, paths, sizes, SHA-256 and optional RPO freshness; never restores data.');
  process.exit(args[0] === '--help' ? 0 : 1);
}
const maxAgeHours = args.length === 3 ? Number(args[2]) : undefined;
try { console.log(JSON.stringify({ status: 'PASS', ...await verifyBackup(args[0], { maxAgeHours }) }, null, 2)); }
catch (error) { console.error(`FAIL: ${error.message}`); process.exitCode = 1; }
