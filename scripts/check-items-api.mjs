// Real HTTP checks, isolated integration backend only. No deletion or credential output.
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
if (process.argv[2] === '--help') {
  console.log('TEST_ADMIN_PASSWORD required. Run node scripts/check-items-api.mjs --confirm-test-environment. Creates two synthetic verified users and several items/images on 127.0.0.1:18080 only. Logs out its own sessions, retains fixtures.'); process.exit(0);
}
if (process.argv.length !== 3 || process.argv[2] !== '--confirm-test-environment' || !process.env.TEST_ADMIN_PASSWORD) throw new Error('Explicit isolated-test confirmation and TEST_ADMIN_PASSWORD required');
const base = 'http://127.0.0.1:18080';
let checks = 0, stage = 'preflight';
const sessions = [];
const suffix = randomBytes(6).toString('hex');
const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jJ1kAAAAASUVORK5CYII=', 'base64');
function check(value, message) { assert.ok(value, message); checks++; }
async function api(method, path, token, body, expected = 200, errorCode) {
  const response = await fetch(base + path, { method, redirect: 'error', signal: AbortSignal.timeout(20000),
    headers: { ...(token ? { 'X-Token': token } : {}), ...(body && !(body instanceof FormData) ? { 'Content-Type': 'application/json' } : {}) },
    body: body instanceof FormData ? body : body === undefined ? undefined : JSON.stringify(body) });
  check(response.status === expected, `${stage}: ${method} ${path.split('?')[0]} expected ${expected}, got ${response.status}`);
  check(response.headers.get('cache-control')?.includes('no-store'), 'private no-store');
  const envelope = await response.json();
  check(envelope.code === (expected === 200 ? 0 : -1), 'envelope matches HTTP');
  if (errorCode) check(envelope.errorCode === errorCode, `expected ${errorCode}, got ${envelope.errorCode}`);
  return envelope.data;
}
async function image(id, token, expected = 200) {
  const response = await fetch(`${base}/api/uploads/images/${id}`, { headers: token ? { 'X-Token': token } : {}, redirect: 'error' });
  check(response.status === expected, `${stage}: image expected ${expected}, got ${response.status}`);
  check(response.headers.get('cache-control')?.includes('no-store'), 'image no-store');
  if (expected === 200) { check(response.headers.get('content-type') === 'image/png', 'actual PNG MIME'); check((await response.arrayBuffer()).byteLength === png.length, 'exact bytes'); }
}
async function upload(token, bytes = png, type = 'text/plain', expected = 200) {
  const form = new FormData(); form.append('file', new Blob([bytes], { type }), '../../untrusted-name.txt');
  return api('POST', '/api/uploads/images', token, form, expected);
}
const content = { title: `测试蓝色水杯-${suffix}`, description: '合成测试：图书馆蓝色水杯，不含真实个人资料。', type: 'FOUND', category: 'TEST_ONLY', location: 'TEST_LIBRARY', occurredAt: null };
try {
  check((await api('GET', '/api/public/config')).isTest === true, 'synthetic environment');
  const admin = await api('POST', '/api/auth/login', null, { username: 'admin', password: process.env.TEST_ADMIN_PASSWORD }); sessions.push(admin.token);
  async function user(index) {
    const credentials = { username: `items_${suffix}_${index}`, password: randomBytes(16).toString('hex') };
    await api('POST', '/api/auth/register', null, { ...credentials, nickname: `物品合成同学${index}` });
    const u = await api('POST', '/api/auth/login', null, credentials); sessions.push(u.token);
    await api('POST', '/api/verifications/me', u.token, { expectedVersion: 0, realName: '合成测试申请人', studentNumber: `TEST-${suffix}-${index}`, statement: '仅用于自动化测试' });
    const v = await api('GET', `/api/admin/verifications/${u.userId}`, admin.token);
    await api('POST', `/api/admin/verifications/${u.userId}/review`, admin.token, { applicationId: v.currentApplication.id, expectedVersion: v.summary.version, decision: 'APPROVED', method: 'IN_PERSON', evidenceSummary: '合成测试，不代表真实核验', validThrough: new Date(Date.now() + 86400000 * 3).toISOString().slice(0, 10), internalNote: 'PRIVATE_IDENTITY_NOTE' });
    return u;
  }
  const owner = await user(1), other = await user(2);
  stage = 'upload boundaries';
  const media = await upload(owner.token);
  check(media.mediaType === 'image/png' && media.state === 'TEMPORARY', 'decode bytes, ignore claimed MIME');
  check(!JSON.stringify(media).includes('storageKey') && !JSON.stringify(media).includes('sha256'), 'no storage internals');
  await image(media.id, owner.token); await image(media.id, other.token, 404); await image(media.id, admin.token, 404); await image(media.id, null, 401);
  await upload(owner.token, Buffer.from('<svg onload="alert(1)"/>'), 'image/png', 400);
  await upload(owner.token, Buffer.alloc(5 * 1024 * 1024 + 1), 'image/png', 413);
  stage = 'create validation and binding';
  await api('POST', '/api/items', owner.token, { ...content, imageIds: [media.id, media.id] }, 400);
  await api('POST', '/api/items', owner.token, { ...content, imageIds: null }, 400);
  await api('POST', '/api/items', owner.token, { ...content, occurredAt: '2099-01-01' }, 400);
  await api('POST', '/api/items', other.token, { ...content, imageIds: [media.id] }, 404);
  let item = await api('POST', '/api/items', owner.token, { ...content, imageIds: [media.id] });
  const id = item.id, path = `/api/items/${id}`, adminPath = `/api/admin/items/${id}`;
  check(item.status === 'PENDING' && item.version === 0 && item.contentVersion === 1 && item.images[0].state === 'BOUND', 'initial versions and atomic binding');
  check(Math.abs(Date.now() - Date.parse(item.createdAt)) < 60000, 'new timestamp is current UTC instant');
  await api('GET', path, other.token, undefined, 404); await image(media.id, other.token, 404); await image(media.id, admin.token);
  await api('POST', '/api/items', owner.token, { ...content, imageIds: [media.id] }, 409);
  await api('PUT', path, owner.token, content, 400);
  await api('PUT', path, other.token, { ...content, expectedVersion: 0 }, 404);
  await api('GET', `${path}/matches`, other.token, undefined, 404);
  stage = 'review privacy and edit resubmission';
  item = await api('PUT', `${adminPath}/review`, admin.token, { status: 'REJECTED', expectedVersion: 0, reason: '测试：请补充物品特征', internalNote: 'PRIVATE_ITEM_NOTE' });
  check(item.version === 1 && item.contentVersion === 1 && item.internalNote === 'PRIVATE_ITEM_NOTE', 'review only increments row version');
  const own = await api('GET', path, owner.token);
  check(own.reviewReason && !JSON.stringify(own).includes('PRIVATE_ITEM_NOTE') && !('internalNote' in own), 'owner sees feedback not internal note');
  await api('PUT', `${adminPath}/review`, admin.token, { status: 'APPROVED', expectedVersion: 0 }, 409, 'VERSION_CONFLICT');
  item = await api('PUT', path, owner.token, { ...content, expectedVersion: 1 });
  check(item.images.length === 1 && item.contentVersion === 2 && item.version === 2 && item.reviewReason === null, 'omitted imageIds preserved and review cleared');
  const concurrent = await Promise.all(['APPROVED', 'REJECTED'].map(status => fetch(`${base}${adminPath}/review`, { method: 'PUT', redirect: 'error', headers: { 'X-Token': admin.token, 'Content-Type': 'application/json' }, body: JSON.stringify({ status, expectedVersion: 2, ...(status === 'REJECTED' ? { reason: '竞争测试' } : {}) }) })));
  check(concurrent.map(r => r.status).sort().join(',') === '200,409', 'one winner for same version');
  item = await api('GET', path, owner.token);
  if (item.status === 'REJECTED') item = await api('PUT', path, owner.token, { ...content, expectedVersion: item.version });
  if (item.status === 'PENDING') item = await api('PUT', `${adminPath}/review`, admin.token, { status: 'APPROVED', expectedVersion: item.version });
  const publicItem = await api('GET', path, other.token);
  check(!('timeline' in publicItem) && !('reviewReason' in publicItem) && !('internalNote' in publicItem), 'public detail omits private keys entirely');
  check(!('contact' in publicItem) && !('realName' in publicItem), 'no identity/contact fields'); await image(media.id, other.token);
  stage = 'paging and compatibility';
  const page = await api('GET', `/api/items/page?keyword=${encodeURIComponent(suffix)}&page=1&pageSize=1`, other.token);
  check(page.records.length === 1 && page.total === 1 && page.pageSize === 1, 'public paged filter');
  check(!('description' in page.records[0]), 'summary is not full entity');
  const legacy = await api('GET', `/api/items?keyword=${suffix}`, other.token);
  check(Array.isArray(legacy) && legacy.length === 1 && !('version' in legacy[0]) && !legacy[0].createdAt.endsWith('Z'), 'legacy array shape/local timestamp retained');
  await api('GET', '/api/items/page?pageSize=51', owner.token, undefined, 400);
  check((await api('GET', '/api/items/page?page=99999', owner.token)).records.length === 0, 'empty page is array');
  check((await api('GET', `/api/admin/items?itemId=${id}`, admin.token)).records[0].id === id, 'admin ID filter');
  check(Array.isArray(await api('GET', '/api/admin/items/pending', admin.token)), 'legacy admin pending array');
  stage = 'remove, atomic rollback and closure';
  item = await api('PUT', path, owner.token, { ...content, imageIds: [], expectedVersion: item.version });
  await image(media.id, owner.token, 404); await image(media.id, admin.token, 404);
  await api('POST', '/api/items', owner.token, { ...content, imageIds: [media.id] }, 409);
  await api('PUT', path, owner.token, { ...content, title: 'MUST_ROLL_BACK', expectedVersion: item.version, imageIds: [999999999] }, 404);
  check((await api('GET', path, owner.token)).title === content.title, 'failed binding rolls back content/version');
  await api('POST', `${path}/close`, owner.token, { expectedVersion: item.version, closeReason: 'FOUND_BY_OWNER', reason: '测试' }, 400);
  item = await api('POST', `${path}/close`, owner.token, { expectedVersion: item.version, closeReason: 'WITHDRAWN', reason: '测试结束' });
  check(item.status === 'CLOSED' && item.closeReason === 'WITHDRAWN', 'owner closure');
  await api('PUT', path, owner.token, { ...content, expectedVersion: item.version }, 409, 'STATE_CONFLICT');
  await api('GET', path, other.token, undefined, 404);
  const lost = await api('POST', '/api/items', owner.token, { ...content, type: 'LOST' });
  check((await api('POST', `/api/items/${lost.id}/close`, owner.token, { expectedVersion: 0, closeReason: 'FOUND_BY_OWNER', reason: '测试找回' })).closeReason === 'FOUND_BY_OWNER', 'LOST owner found');
  const down = await api('POST', '/api/items', owner.token, content);
  check((await api('POST', `/api/admin/items/${down.id}/close`, admin.token, { expectedVersion: 0, reason: '测试下架' })).closeReason === 'ADMIN_REMOVED', 'dedicated admin closure');
  console.log(`PASS ${checks} item/media HTTP checks. Synthetic users ${owner.userId},${other.userId}; items ${id},${lost.id},${down.id}. No records deleted.`);
} catch (error) { console.error(`FAIL ${stage}: ${error.message}`); process.exitCode = 1; }
finally { for (const token of sessions) { try { await api('POST', '/api/auth/logout', token); } catch { console.error('Session logout not confirmed'); process.exitCode = 1; } } }
