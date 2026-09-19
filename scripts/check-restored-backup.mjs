// Read-only business smoke for an explicitly isolated restored backup on fixed loopback port 18082.
import assert from 'node:assert/strict';

if (process.argv[2] === '--help') {
  console.log('RESTORE_SMOKE_ADMIN_PASSWORD required. Run node scripts/check-restored-backup.mjs --confirm-isolated-restore-smoke against the dedicated 127.0.0.1:18082 restore service.');
  process.exit(0);
}
if (process.argv.length !== 3 || process.argv[2] !== '--confirm-isolated-restore-smoke' || !process.env.RESTORE_SMOKE_ADMIN_PASSWORD)
  throw new Error('Explicit isolated-restore confirmation and RESTORE_SMOKE_ADMIN_PASSWORD required');

const base = 'http://127.0.0.1:18082';
let checks = 0, token, stage = 'preflight';
function check(value, message) { assert.ok(value, message); checks++; }
async function request(path, options = {}, expected = 200) {
  const response = await fetch(base + path, { redirect: 'error', signal: AbortSignal.timeout(10000), ...options,
    headers: { ...(token ? { 'X-Token': token } : {}), ...(options.headers ?? {}) } });
  check(response.status === expected, `${stage}: ${path.split('?')[0]} expected ${expected}, got ${response.status}`);
  check(response.headers.get('cache-control')?.includes('no-store'), `${stage}: no-store`);
  return response;
}
async function api(path, options, expected = 200) {
  const response = await request(path, options, expected);
  const envelope = await response.json();
  check(envelope.code === (expected === 200 ? 0 : -1), `${stage}: envelope matches HTTP`);
  return envelope.data;
}

try {
  stage = 'health contract';
  for (const endpoint of ['live', 'ready']) {
    const response = await request(`/api/health/${endpoint}`);
    check(JSON.stringify(await response.json()) === '{"status":"UP"}', `${endpoint} exact UP body`);
  }
  stage = 'restored environment';
  const config = await api('/api/public/config');
  check(config.isTest === true && config.aiEnabled === false, 'test mode with AI disabled');
  await api('/api/admin/items?page=1&pageSize=1', undefined, 401);
  const login = await api('/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: process.env.RESTORE_SMOKE_ADMIN_PASSWORD }) });
  token = login.token;
  check(typeof token === 'string' && token.length >= 32 && login.role === 'ADMIN', 'restored admin login');

  stage = 'restored item inventory';
  const page = await api('/api/admin/items?page=1&pageSize=50');
  check(Number.isSafeInteger(page.total) && page.total > 0, 'restored items present');
  check(Array.isArray(page.records) && page.records.length > 0, 'restored item page present');
  const images = new Map();
  for (const summary of page.records) {
    check(Number.isSafeInteger(summary.id) && summary.id > 0, 'valid restored item id');
    const detail = await api(`/api/admin/items/${summary.id}`);
    check(detail.id === summary.id && Array.isArray(detail.images), 'restored item detail and binding list');
    for (const image of detail.images) {
      if (Number.isSafeInteger(image.id) && image.id > 0 && ['image/png', 'image/jpeg'].includes(image.mediaType))
        images.set(image.id, image.mediaType);
    }
  }
  check(images.size > 0, 'at least one restored item/media binding');
  stage = 'restored media bytes';
  for (const [id, mediaType] of images) {
    const response = await request(`/api/uploads/images/${id}`);
    check(response.headers.get('content-type') === mediaType, 'restored image MIME matches binding metadata');
    check((await response.arrayBuffer()).byteLength > 0, 'restored image has bytes');
  }
  console.log(`PASS ${checks} restored-backup HTTP checks; ${page.records.length}/${page.total} items sampled, ${images.size} bound images read. No business mutation requested.`);
} catch (error) {
  console.error(`FAIL ${stage}: ${error.message}`);
  process.exitCode = 1;
} finally {
  if (token) {
    try {
      await api('/api/auth/logout', { method: 'POST' });
      token = undefined;
    } catch {
      console.error('Restore smoke session logout not confirmed');
      process.exitCode = 1;
    }
  }
}
