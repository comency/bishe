import { createHash } from 'node:crypto';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, rename, symlink, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { safeBackupPath, verifyBackup } from '../lib/backup-manifest.mjs';
const root = resolve('.local/backup-tool-tests'); await mkdir(root, { recursive: true });
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
async function fixture() {
  const dir = await mkdtemp(join(root, 'case-')), media = join(dir, 'media'); await mkdir(media);
  const files = [['database.sql', Buffer.from('synthetic sql')], ['media/a.png', Buffer.from('synthetic image')]];
  for (const [path, bytes] of files) await writeFile(join(dir, path), bytes);
  const artifacts = files.map(([path, bytes]) => ({ path, size: bytes.length, sha256: sha256(bytes) }));
  await writeFile(join(dir, 'manifest.json'), JSON.stringify({ format: 1, createdAt: '2026-09-18T00:00:00Z', writesQuiesced: true,
    databaseDump: 'database.sql', mediaDirectory: 'media', mediaFiles: 1, artifacts }));
  return dir;
}
async function alter(dir, change) { const path = join(dir, 'manifest.json'), value = JSON.parse(await readFile(path)); change(value); await writeFile(path, JSON.stringify(value)); }
test('valid backup verifies exact inventory and hashes', async () => assert.deepEqual(await verifyBackup(await fixture()),
  { createdAt: '2026-09-18T00:00:00Z', artifacts: 2, mediaFiles: 1, writesQuiesced: true }));
for (const path of ['../secret', '/absolute', 'C:/secret', 'media/../../secret', 'media\\x', 'media//x', 'media/.env', 'other/file'])
  test(`reject unsafe backup path ${path}`, () => assert.throws(() => safeBackupPath(path)));
test('tampered dump fails hash verification', async () => { const dir = await fixture(); await writeFile(join(dir, 'database.sql'), 'synthetic XXX'); await assert.rejects(verifyBackup(dir), /content mismatch/); });
test('undeclared file fails exact inventory', async () => { const dir = await fixture(); await writeFile(join(dir, 'media/extra.png'), 'x'); await assert.rejects(verifyBackup(dir), /undeclared/); });
test('missing media file fails exact inventory', async () => { const dir = await fixture(); await rename(join(dir, 'media/a.png'), join(dir, 'media/moved.png')); await assert.rejects(verifyBackup(dir), /missing or undeclared/); });
test('false quiescence claim is rejected', async () => { const dir = await fixture(); await alter(dir, value => { value.writesQuiesced = false; }); await assert.rejects(verifyBackup(dir), /incomplete/); });
test('duplicate manifest path is rejected', async () => { const dir = await fixture(); await alter(dir, value => value.artifacts.push(value.artifacts[0])); await assert.rejects(verifyBackup(dir), /Duplicate/); });
test('junction cannot pull external files into backup', async () => { const dir = await fixture(), outside = await mkdtemp(join(root, 'outside-')); await writeFile(join(outside, 'x'), 'x'); await symlink(outside, join(dir, 'media/external'), 'junction'); await assert.rejects(verifyBackup(dir), /links and junctions/); });
