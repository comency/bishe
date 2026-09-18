import { createHash } from 'node:crypto';
import { readdir, lstat, readFile, writeFile } from 'node:fs/promises';
import { resolve, relative, sep } from 'node:path';

const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
const required = ['backend/app.jar', 'frontend/index.html', 'source.zip', 'README.md', 'RELEASE-CHECKLIST.md'];
export function safeArtifactPath(path) {
  if (typeof path !== 'string' || !/^[A-Za-z0-9_./-]+$/.test(path) || path.startsWith('/') ||
      path.split('/').some(part => !part || part.startsWith('.') || part.endsWith('.') || /^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)/i.test(part))) {
    throw new Error('Invalid candidate artifact path');
  }
  if (!required.includes(path) && !path.startsWith('frontend/')) throw new Error('Unexpected artifact category');
  return path;
}
async function files(root) {
  const result = [];
  async function visit(directory) {
    for (const item of await readdir(directory, { withFileTypes: true })) {
      const path = resolve(directory, item.name), stat = await lstat(path);
      if (stat.isSymbolicLink()) throw new Error('Symlinks/junctions are forbidden in candidate artifacts');
      if (stat.isDirectory()) await visit(path);
      else if (stat.isFile()) {
        const name = relative(root, path).split(sep).join('/');
        if (name !== 'manifest.json') result.push(safeArtifactPath(name));
      } else throw new Error('Only ordinary candidate files are allowed');
    }
  }
  if ((await lstat(root)).isSymbolicLink()) throw new Error('Candidate root cannot be a link');
  await visit(root);
  const folded = result.map(name => name.toLowerCase());
  if (new Set(folded).size !== folded.length) throw new Error('Case-insensitive duplicate artifact paths');
  return result.sort();
}
function validateMetadata(value, format) {
  if (!value || !/^[a-f0-9]{40}$/.test(value.revision) || value.kind !== 'local-candidate' || value.productionApproved !== false ||
      typeof value.builtAt !== 'string' || !Number.isFinite(Date.parse(value.builtAt))) throw new Error('Invalid candidate identity/status metadata');
  const backend = value.backendTests;
  if (!backend || !['tests', 'passed', 'skipped'].every(key => Number.isSafeInteger(backend[key]) && backend[key] >= 0) ||
      backend.passed < 1 || backend.tests !== backend.passed + backend.skipped || backend.failures !== 0 || backend.errors !== 0)
    throw new Error('Candidate requires an explicit successful backend test summary');
  if (value.frontendVerified !== true || value.sourceSnapshotBuild !== true) throw new Error('Candidate build verification missing');
  if (format >= 2) {
    const tools = value.toolTests;
    if (!tools || !['tests', 'passed', 'skipped', 'failures'].every(key => Number.isSafeInteger(tools[key]) && tools[key] >= 0) ||
        tools.passed < 1 || tools.tests !== tools.passed + tools.skipped || tools.failures !== 0)
      throw new Error('Candidate requires an explicit successful tool test summary');
  }
  if (format >= 3) {
    const frontend = value.frontendTests;
    if (!frontend || !['tests', 'passed', 'skipped', 'failures'].every(key => Number.isSafeInteger(frontend[key]) && frontend[key] >= 0) ||
        frontend.passed < 1 || frontend.tests !== frontend.passed + frontend.skipped || frontend.failures !== 0)
      throw new Error('Candidate requires an explicit successful frontend test summary');
  }
}
export async function createManifest(root, metadata) {
  root = resolve(root); validateMetadata(metadata, 3);
  const names = await files(root);
  for (const name of required) if (!names.includes(name)) throw new Error(`Required candidate artifact missing: ${name}`);
  const entries = [];
  for (const path of names) { const bytes = await readFile(resolve(root, path)); entries.push({ path, bytes: bytes.length, sha256: sha256(bytes) }); }
  const manifest = { format: 3, ...metadata, artifacts: entries };
  await writeFile(resolve(root, 'manifest.json'), JSON.stringify(manifest, null, 2), { flag: 'wx' });
  return manifest;
}
export async function verifyManifest(root) {
  root = resolve(root);
  if ((await lstat(root)).isSymbolicLink()) throw new Error('Candidate root cannot be a link');
  const manifestInfo = await lstat(resolve(root, 'manifest.json'));
  if (!manifestInfo.isFile() || manifestInfo.isSymbolicLink() || manifestInfo.size > 2 * 1024 ** 2) throw new Error('Manifest must be a bounded ordinary file');
  let manifest;
  try { manifest = JSON.parse((await readFile(resolve(root, 'manifest.json'), 'utf8')).replace(/^\uFEFF/, '')); }
  catch { throw new Error('Invalid candidate manifest JSON'); }
  if (![1, 2, 3].includes(manifest.format) || !Array.isArray(manifest.artifacts)) throw new Error('Unsupported candidate manifest');
  validateMetadata(manifest, manifest.format);
  const declared = [], folded = new Set();
  for (const artifact of manifest.artifacts) {
    const path = safeArtifactPath(artifact?.path);
    if (folded.has(path.toLowerCase())) throw new Error('Duplicate manifest artifact');
    folded.add(path.toLowerCase()); declared.push(path);
    if (!Number.isSafeInteger(artifact.bytes) || artifact.bytes < 0 || !/^[a-f0-9]{64}$/.test(artifact.sha256)) throw new Error('Invalid artifact length/digest');
  }
  const actual = await files(root);
  if (JSON.stringify(actual) !== JSON.stringify(declared.sort())) throw new Error('Candidate has missing or undeclared files');
  for (const path of required) if (!declared.includes(path)) throw new Error('Candidate is incomplete');
  for (const artifact of manifest.artifacts) {
    const bytes = await readFile(resolve(root, artifact.path));
    if (bytes.length !== artifact.bytes || sha256(bytes) !== artifact.sha256) throw new Error(`Candidate content mismatch: ${artifact.path}`);
  }
  return { revision: manifest.revision, artifacts: actual.length, backendTests: manifest.backendTests,
    frontendTests: manifest.frontendTests ?? null, toolTests: manifest.toolTests ?? null, productionApproved: false };
}
