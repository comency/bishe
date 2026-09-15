// Read-only checks. Supply the selected dedicated account as DB_USERNAME/DB_PASSWORD.
import { spawnSync } from 'node:child_process';
const mode = process.argv[2];
if (!['dev', 'integration'].includes(mode) || !process.env.DB_USERNAME || !process.env.DB_PASSWORD) {
  console.error('Usage: node scripts/check-database-isolation.mjs dev|integration (requires selected DB_USERNAME/DB_PASSWORD)');
  process.exit(1);
}
const own = mode === 'dev' ? 'lost_found' : 'lost_found_test';
const other = mode === 'dev' ? 'lost_found_test' : 'lost_found';
const expectedUser = mode === 'dev' ? 'lost_found_app' : 'lost_found_test_app';
if (process.env.DB_USERNAME !== expectedUser) { console.error('Use the dedicated environment account, not the bootstrap administrator.'); process.exit(1); }
function sql(query) {
  return spawnSync(process.env.MYSQL_CLIENT ?? 'E:/MySQL/MySQL Server 8.0/bin/mysql.exe', [
    '--no-defaults', '--protocol=TCP', '--host=127.0.0.1', '--port=13306',
    `--user=${process.env.DB_USERNAME}`, '--connect-timeout=5', '--batch', '--skip-column-names',
  ], { input: query, encoding: 'utf8', windowsHide: true, timeout: 10000,
    env: { ...process.env, MYSQL_PWD: process.env.DB_PASSWORD } });
}
const inspection = sql(`SELECT @@port; SELECT COUNT(*) FROM ${own}.flyway_schema_history WHERE version='1' AND success=1; SELECT COUNT(*) FROM ${own}.users; SELECT COUNT(*) FROM ${own}.items;`);
const values = inspection.stdout?.trim().split(/\s+/);
if (inspection.error || inspection.status !== 0 || values?.[0] !== '13306' || values?.[1] !== '1') {
  console.error('Own schema or V1 migration validation failed.'); process.exit(1);
}
const cross = sql(`SELECT COUNT(*) FROM ${other}.users;`);
if (cross.error || cross.status === 0 || !/ERROR (1044|1142) /.test(cross.stderr ?? '')) {
  console.error('Cross-environment access was not denied as expected.'); process.exit(1);
}
console.log(`PASS: ${mode} account, MySQL 13306, V1 applied, opposite schema denied. Own row counts users=${values[2]}, items=${values[3]}.`);
