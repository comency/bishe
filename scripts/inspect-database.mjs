// Read-only inspection of the explicitly scoped local MySQL instance.
// Passwords are passed only to the child environment, never command-line arguments.
import { spawnSync } from 'node:child_process';

const username = process.env.DB_USERNAME;
const password = process.env.DB_PASSWORD;
if (!username || !password) {
  console.error('Set DB_USERNAME and DB_PASSWORD in this process first. No connection attempted.');
  process.exit(1);
}
const sql = `
SELECT @@port AS port, @@version AS version, @@hostname AS server_name,
       @@time_zone AS session_time_zone, @@system_time_zone AS system_time_zone;
SELECT SCHEMA_NAME, DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME
  FROM information_schema.SCHEMATA WHERE SCHEMA_NAME IN ('lost_found','lost_found_test');
SELECT TABLE_SCHEMA, TABLE_NAME, TABLE_TYPE, TABLE_ROWS
  FROM information_schema.TABLES WHERE TABLE_SCHEMA IN ('lost_found','lost_found_test')
  ORDER BY TABLE_SCHEMA, TABLE_NAME;
SELECT TABLE_SCHEMA, TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_KEY
  FROM information_schema.COLUMNS WHERE TABLE_SCHEMA IN ('lost_found','lost_found_test')
  ORDER BY TABLE_SCHEMA, TABLE_NAME, ORDINAL_POSITION;
`;
const result = spawnSync(process.env.MYSQL_CLIENT ?? 'E:/MySQL/MySQL Server 8.0/bin/mysql.exe', [
  '--no-defaults', '--protocol=TCP', '--host=127.0.0.1', '--port=13306',
  `--user=${username}`, '--connect-timeout=5', '--batch', '--default-character-set=utf8mb4',
], { input: sql, encoding: 'utf8', windowsHide: true, timeout: 15000,
  env: { ...process.env, MYSQL_PWD: password }, maxBuffer: 2 * 1024 * 1024 });
if (result.error || result.status !== 0) {
  console.error(result.error ? 'MySQL client could not complete inspection.' : result.stderr);
  process.exit(1);
}
console.log(result.stdout);
