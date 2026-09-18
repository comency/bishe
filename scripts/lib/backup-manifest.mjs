import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { lstat, readFile, readdir } from 'node:fs/promises';
import { relative, resolve, sep } from 'node:path';

async function verifyFile(path, expectedSize, expectedDigest) {
  const hash = createHash('sha256');
  let size = 0;
  for await (const chunk of createReadStream(path)) {
    size += chunk.length;
    hash.update(chunk);
  }
  return size === expectedSize && hash.digest('hex') === expectedDigest;
}
export function safeBackupPath(path) {
  if (typeof path !== 'string' || !/^[A-Za-z0-9_./-]+$/.test(path) || path.startsWith('/') ||
      path.split('/').some(part => !part || part === '.' || part === '..' || part.startsWith('.') || part.endsWith('.') ||
        /^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)/i.test(part))) throw new Error('Invalid backup artifact path');
  if (path !== 'database.sql' && !path.startsWith('media/')) throw new Error('Unexpected backup artifact category');
  return path;
}
async function inventory(root) {
  const result = [];
  if ((await lstat(root)).isSymbolicLink()) throw new Error('Backup root cannot be a link');
  async function visit(directory) {
    for (const entry of await readdir(directory, { withFileTypes: true })) {
      const full = resolve(directory, entry.name), stat = await lstat(full);
      if (stat.isSymbolicLink()) throw new Error('Backup links and junctions are forbidden');
      if (stat.isDirectory()) await visit(full);
      else if (stat.isFile()) {
        const path = relative(root, full).split(sep).join('/');
        if (path !== 'manifest.json') result.push(safeBackupPath(path));
      } else throw new Error('Only ordinary backup files are allowed');
    }
  }
  await visit(root);
  const folded = result.map(path => path.toLowerCase());
  if (new Set(folded).size !== folded.length) throw new Error('Case-insensitive duplicate backup paths');
  return result.sort();
}
export async function verifyBackup(root) {
  root = resolve(root);
  const manifestInfo = await lstat(resolve(root, 'manifest.json'));
  if (!manifestInfo.isFile() || manifestInfo.isSymbolicLink() || manifestInfo.size > 2 * 1024 ** 2)
    throw new Error('Backup manifest must be a bounded ordinary file');
  let manifest;
  try { manifest = JSON.parse((await readFile(resolve(root, 'manifest.json'), 'utf8')).replace(/^\uFEFF/, '')); }
  catch { throw new Error('Invalid backup manifest JSON'); }
  if (manifest.format !== 1 || manifest.writesQuiesced !== true || manifest.databaseDump !== 'database.sql' ||
      manifest.mediaDirectory !== 'media' || !Number.isSafeInteger(manifest.mediaFiles) || manifest.mediaFiles < 0 ||
      typeof manifest.createdAt !== 'string' || !Number.isFinite(Date.parse(manifest.createdAt)) || !Array.isArray(manifest.artifacts))
    throw new Error('Unsupported or incomplete backup manifest');
  const declared = [], folded = new Set();
  for (const artifact of manifest.artifacts) {
    const path = safeBackupPath(artifact?.path);
    if (folded.has(path.toLowerCase())) throw new Error('Duplicate backup manifest artifact');
    folded.add(path.toLowerCase()); declared.push(path);
    if (!Number.isSafeInteger(artifact.size) || artifact.size < 0 || !/^[a-f0-9]{64}$/.test(artifact.sha256))
      throw new Error('Invalid backup artifact length or digest');
  }
  const actual = await inventory(root);
  if (JSON.stringify(actual) !== JSON.stringify(declared.sort())) throw new Error('Backup has missing or undeclared files');
  if (!declared.includes('database.sql') || declared.filter(path => path.startsWith('media/')).length !== manifest.mediaFiles)
    throw new Error('Backup database/media inventory is incomplete');
  for (const artifact of manifest.artifacts) {
    if (!await verifyFile(resolve(root, artifact.path), artifact.size, artifact.sha256))
      throw new Error(`Backup content mismatch: ${artifact.path}`);
  }
  return { createdAt: manifest.createdAt, artifacts: actual.length, mediaFiles: manifest.mediaFiles, writesQuiesced: true };
}
