import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, readFile, rename, symlink } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { createManifest, verifyManifest, safeArtifactPath } from '../lib/release-manifest.mjs';
const root = resolve('.local/release-tool-tests'); await mkdir(root, { recursive: true });
const metadata = () => ({ revision: 'a'.repeat(40), kind: 'local-candidate', productionApproved: false, builtAt: '2026-09-16T00:00:00Z',
  backendTests: { tests: 3, passed: 2, skipped: 1, failures: 0, errors: 0 }, frontendVerified: true, sourceSnapshotBuild: true });
async function fixture() {
  const dir = await mkdtemp(join(root, 'case-'));
  await mkdir(join(dir, 'backend')); await mkdir(join(dir, 'frontend'));
  for (const path of ['backend/app.jar', 'frontend/index.html', 'source.zip', 'README.md', 'RELEASE-CHECKLIST.md']) await writeFile(join(dir, path), `synthetic ${path}`);
  return dir;
}
async function alter(dir, change) {
  const path = join(dir, 'manifest.json'), value = JSON.parse(await readFile(path, 'utf8')); change(value); await writeFile(path, JSON.stringify(value));
}
test('valid candidate round-trips with explicit skipped-test count', async () => {
  const dir = await fixture(); await createManifest(dir, metadata());
  const result = await verifyManifest(dir); assert.equal(result.artifacts, 5); assert.equal(result.backendTests.skipped, 1); assert.equal(result.productionApproved, false);
});
for (const path of ['../secret', '/absolute', 'C:/secret', 'frontend/../../secret', 'frontend\\secret', 'frontend//x', 'frontend/./x', 'frontend/.env', 'frontend/con.txt', 'frontend/x.', 'credentials.xml'])
  test(`reject unsafe path ${path}`, () => assert.throws(() => safeArtifactPath(path)));
test('changed artifact fails even when its length is unchanged', async () => {
  const dir = await fixture(); await createManifest(dir, metadata()); const path = join(dir, 'backend/app.jar'), original = await readFile(path);
  await writeFile(path, Buffer.alloc(original.length, 65)); await assert.rejects(verifyManifest(dir), /content mismatch/);
});
test('undeclared file fails exact inventory', async () => {
  const dir = await fixture(); await createManifest(dir, metadata()); await writeFile(join(dir, 'frontend/extra.js'), 'synthetic'); await assert.rejects(verifyManifest(dir), /undeclared/);
});
test('missing artifact fails exact inventory without deleting fixture', async () => {
  const dir = await fixture(); await createManifest(dir, metadata()); await rename(join(dir, 'source.zip'), join(dir, 'frontend/moved.zip')); await assert.rejects(verifyManifest(dir), /missing or undeclared/);
});
test('duplicate manifest entry fails', async () => {
  const dir = await fixture(); await createManifest(dir, metadata()); await alter(dir, m => m.artifacts.push(m.artifacts[0])); await assert.rejects(verifyManifest(dir), /Duplicate/);
});
test('tampered traversal is rejected before reading outside candidate', async () => {
  const dir = await fixture(); await createManifest(dir, metadata()); await alter(dir, m => {m.artifacts[0].path='../outside';}); await assert.rejects(verifyManifest(dir), /Invalid candidate artifact path/);
});
test('manifest cannot relabel candidate as production-approved', async () => {
  const dir = await fixture(); await createManifest(dir, metadata()); await alter(dir, m => {m.productionApproved=true;}); await assert.rejects(verifyManifest(dir), /identity\/status/);
});
test('a failed backend summary never creates a manifest', async () => {
  const dir = await fixture(), value = metadata(); value.backendTests.failures=1; await assert.rejects(createManifest(dir, value), /successful backend/);
});
test('manifest is create-only, never silently overwritten', async () => {
  const dir = await fixture(); await createManifest(dir, metadata()); await assert.rejects(createManifest(dir, metadata()), /EEXIST/);
});
test('directory junction cannot pull external files into candidate', async () => {
  const dir = await fixture(), outside = await mkdtemp(join(root, 'outside-')); await writeFile(join(outside, 'synthetic.txt'), 'not an artifact');
  await symlink(outside, join(dir, 'frontend/external'), 'junction'); await assert.rejects(createManifest(dir, metadata()), /Symlinks\/junctions/);
});
test('malformed manifest never echoes its potentially private contents', async () => {
  const dir = await fixture(); await writeFile(join(dir, 'manifest.json'), 'SYNTHETIC_PRIVATE_CANARY malformed');
  await assert.rejects(verifyManifest(dir), error => error.message === 'Invalid candidate manifest JSON');
});
test('root junction is refused before manifest access', async () => {
  const dir = await fixture(), link = dir + '-link'; await createManifest(dir, metadata()); await symlink(dir, link, 'junction');
  await assert.rejects(verifyManifest(link), /root cannot be a link/);
});
