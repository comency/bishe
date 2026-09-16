// Real HTTP claim/hand-over checks. Fixed isolated backend, synthetic fixtures, no database cleanup.
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
if (process.argv[2] === '--help') { console.log('TEST_ADMIN_PASSWORD required. node scripts/check-claims-api.mjs --confirm-test-environment. Creates synthetic accounts, items and claims on 127.0.0.1:18080 only; logs out own sessions; retains fixtures.'); process.exit(0); }
if (process.argv.length !== 3 || process.argv[2] !== '--confirm-test-environment' || !process.env.TEST_ADMIN_PASSWORD) throw new Error('Explicit isolated test confirmation required');
const base = 'http://127.0.0.1:18080', suffix = randomBytes(6).toString('hex'), sessions = [], fixtures = [];
let checks = 0, stage = 'preflight';
function check(ok, message) { assert.ok(ok, message); checks++; }
async function api(method, path, token, body, expected = 200, code) {
  const response = await fetch(base + path, { method, redirect: 'error', signal: AbortSignal.timeout(20000), headers: { ...(token ? { 'X-Token': token } : {}), ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}) }, body: body === undefined ? undefined : JSON.stringify(body) });
  check(response.status === expected, `${stage}: ${method} ${path.split('?')[0]} expected ${expected}, got ${response.status}`);
  check(response.headers.get('cache-control')?.includes('no-store'), 'no-store');
  const envelope = await response.json(); check(envelope.code === (expected === 200 ? 0 : -1), 'envelope code');
  if (code) check(envelope.errorCode === code, `expected ${code}, got ${envelope.errorCode}`);
  if (expected !== 200) check(envelope.data === null && typeof envelope.traceId === 'string', 'no error payload leakage');
  return envelope.data;
}
const summaryKeys = ['id', 'item', 'publisherId', 'applicantId', 'status', 'version', 'createdAt', 'acceptedAt', 'handedOverAt', 'receivedAt', 'completedAt', 'endReason', 'canConfirm'];
function keys(value, allowed) { check(Object.keys(value).sort().join(',') === [...allowed].sort().join(','), 'exact DTO field whitelist'); }
function detail(value, admin = false) {
  keys(value, [...summaryKeys, 'identification', 'timeline', ...(admin ? ['applicantContactSnapshot', 'publisherContactSnapshot', 'internalNote', 'latestResolution'] : ['counterpartContact'])]);
  keys(value.item, ['itemId', 'title', 'type', 'contentVersion']);check(value.item.type === 'FOUND', 'snapshot FOUND');
  check(Number.isInteger(value.version) && value.version >= 0, 'version is integer');
  keys(value.timeline, ['records', 'total', 'page', 'pageSize']);
  for (const event of value.timeline.records) keys(event, ['id', 'action', 'occurredAt', 'message']);
  if (!admin) check(!JSON.stringify(value).includes('PRIVATE_NOTE') && !JSON.stringify(value).includes('PRIVATE_CONCLUSION'), 'ordinary detail has no internal notes');
  return value;
}
try {
  check((await api('GET', '/api/public/config')).isTest, 'test environment only');
  const admin = await api('POST', '/api/auth/login', null, { username: 'admin', password: process.env.TEST_ADMIN_PASSWORD });sessions.push(admin.token);
  async function user(n, verified = true) {
    const credentials = { username: `claims_${suffix}_${n}`, password: randomBytes(16).toString('hex') };
    await api('POST', '/api/auth/register', null, { ...credentials, nickname: `合成认领同学${n}` });const u = await api('POST', '/api/auth/login', null, credentials);sessions.push(u.token);
    if (verified) {
      await api('POST', '/api/verifications/me', u.token, { expectedVersion: 0, realName: '合成测试同学', statement: '不含真实个人信息' });
      const v = await api('GET', `/api/admin/verifications/${u.userId}`, admin.token);
      await api('POST', `/api/admin/verifications/${u.userId}/review`, admin.token, { applicationId: v.currentApplication.id, expectedVersion: v.summary.version, decision: 'APPROVED', method: 'IN_PERSON', evidenceSummary: 'Synthetic only', validThrough: new Date(Date.now() + 86400000 * 3).toISOString().slice(0, 10) });
    }return u;
  }
  const owner = await user(1), applicant = await user(2), other = await user(3), unverified = await user(4, false);
  const content = { title: `合成认领水杯-${suffix}`, description: '合成测试，不含真实资料', type: 'FOUND' };
  async function item(type = 'FOUND') { const i = await api('POST', '/api/items', owner.token, { ...content, type }); fixtures.push(i.id);return api('PUT', `/api/admin/items/${i.id}/review`, admin.token, { expectedVersion: i.version, status: 'APPROVED' }); }
  async function apply(i, u) { return detail(await api('POST', `/api/items/${i.id}/claims`, u.token, { expectedItemVersion: i.version, identification: 'PRIVATE_EVIDENCE', contact: 'PRIVATE_APPLICANT_CONTACT' })); }
  async function revoke(u) { const v = await api('GET', `/api/admin/verifications/${u.userId}`, admin.token);await api('POST', `/api/admin/verifications/${u.userId}/revoke`, admin.token, { expectedVersion: v.summary.version, reason: 'Synthetic eligibility test' }); }
  const i = await item(), path = `/api/items/${i.id}/claims`, create = { expectedItemVersion: i.version, identification: 'PRIVATE_EVIDENCE', contact: 'PRIVATE_APPLICANT_CONTACT' };
  stage = 'admission, validation and privacy';
  await api('POST', path, undefined, create, 401, 'AUTH_REQUIRED');await api('POST', path, unverified.token, create, 403, 'VERIFICATION_REQUIRED');
  await api('POST', path, owner.token, create, 403, 'FORBIDDEN');await api('POST', path, applicant.token, { ...create, identification: ' ' }, 400);
  await api('POST', path, applicant.token, { ...create, contact: 'x'.repeat(101) }, 400);await api('POST', path, applicant.token, { ...create, publisherId: owner.userId }, 400);
  await api('POST', path, applicant.token, { ...create, expectedItemVersion: 0 }, 409, 'VERSION_CONFLICT');
  let a = await apply(i, applicant), b = await apply(i, other);check(a.status === 'APPLIED' && a.counterpartContact === null && a.version === 0, 'initial claim');
  await api('POST', path, applicant.token, create, 409, 'CLAIM_EXISTS');
  check((await api('GET', `/api/items/${i.id}`, applicant.token)).myClaimId === a.id, 'real myClaimId');
  await api('GET', `/api/claims/${a.id}`, other.token, undefined, 404, 'NOT_ACCESSIBLE');await api('GET', `/api/claims/${a.id}`, admin.token, undefined, 403, 'VERIFICATION_REQUIRED');
  await api('GET', `/api/admin/claims/${a.id}`, applicant.token, undefined, 403, 'FORBIDDEN');detail(await api('GET', `/api/admin/claims/${a.id}`, admin.token), true);
  for (const [route, u] of [['mine', applicant], ['incoming', owner]]) { const p = await api('GET', `/api/claims/${route}?itemId=${i.id}&pageSize=1`, u.token);check(p.records.length === 1, 'participant paged list');keys(p.records[0], summaryKeys); }
  await api('GET', '/api/claims/mine?pageSize=51', applicant.token, undefined, 400);await api('GET', '/api/claims/mine?status=INVALID', applicant.token, undefined, 400);
  await api('PUT', `/api/items/${i.id}`, owner.token, { ...content, expectedVersion: i.version }, 409, 'STATE_CONFLICT');await api('POST', `/api/items/${i.id}/close`, owner.token, { expectedVersion: i.version, closeReason: 'WITHDRAWN', reason: 'Synthetic' }, 409, 'STATE_CONFLICT');
  await api('POST', `/api/claims/${a.id}/accept`, applicant.token, { expectedVersion: 0, contact: 'Synthetic' }, 403, 'FORBIDDEN');
  await api('POST', `/api/claims/${a.id}/cancel`, owner.token, { expectedVersion: 0, reason: 'Synthetic' }, 403, 'FORBIDDEN');
  stage = 'acceptance, contact snapshot and double confirmation';
  a = detail(await api('POST', `/api/claims/${a.id}/accept`, owner.token, { expectedVersion: 0, contact: 'PRIVATE_PUBLISHER_CONTACT' }));check(a.counterpartContact === 'PRIVATE_APPLICANT_CONTACT' && a.canConfirm, 'accepted contact projection');
  const occupied = await api('GET', `/api/items/${i.id}`, owner.token);check(occupied.hasAcceptedClaim && occupied.version === i.version + 1 && occupied.contentVersion === i.contentVersion, 'occupation bumps only item row version');
  await api('POST', `/api/claims/${b.id}/accept`, owner.token, { expectedVersion: 0, contact: 'Synthetic' }, 409, 'STATE_CONFLICT');
  await api('POST', `/api/claims/${a.id}/confirm-handover`, applicant.token, undefined, 403, 'FORBIDDEN');
  await api('POST', `/api/claims/${a.id}/confirm-receipt`, applicant.token, {}, 400);await api('POST', `/api/claims/${a.id}/confirm-receipt`, applicant.token, { expectedVersion: 1 }, 400);
  a = detail(await api('POST', `/api/claims/${a.id}/confirm-receipt`, applicant.token));check(a.status === 'ACCEPTED' && a.receivedAt && !a.handedOverAt && !a.canConfirm, 'single confirmation remains accepted');
  const repeated = await api('POST', `/api/claims/${a.id}/confirm-receipt`, applicant.token);check(repeated.version === a.version && repeated.receivedAt === a.receivedAt && repeated.timeline.total === a.timeline.total, 'repeat first confirmation read-only');
  await api('POST', `/api/claims/${a.id}/cancel`, owner.token, { expectedVersion: a.version, reason: 'Synthetic' }, 409, 'STATE_CONFLICT');
  await api('POST', `/api/admin/items/${i.id}/close`, admin.token, { expectedVersion: occupied.version, reason: 'Synthetic' }, 409, 'STATE_CONFLICT');
  a = detail(await api('POST', `/api/claims/${a.id}/confirm-handover`, owner.token));check(a.status === 'COMPLETED' && a.handedOverAt && a.receivedAt && a.completedAt && a.counterpartContact === null, 'atomic returned closure');
  b = detail(await api('GET', `/api/claims/${b.id}`, other.token));check(b.status === 'REJECTED' && b.endReason, 'remaining applications rejected');
  const closed = await api('GET', `/api/items/${i.id}`, owner.token);check(closed.closeReason === 'RETURNED' && !closed.timeline.records.some(e => e.action.startsWith('CLAIM_')), 'item history does not disclose claim events');
  check((await api('POST', `/api/claims/${a.id}/confirm-handover`, owner.token)).version === a.version, 'completed retry does not increment version');
  stage = 'cancel, reject, permanent duplicate and snapshot';
  const second = await item();let c = await apply(second, applicant);const waiting = await apply(second, other);
  c = await api('POST', `/api/claims/${c.id}/accept`, owner.token, { expectedVersion: c.version, contact: 'Synthetic publisher' });
  c = detail(await api('POST', `/api/claims/${c.id}/cancel`, owner.token, { expectedVersion: c.version, reason: 'Synthetic cancel' }));check(c.status === 'CANCELLED' && c.counterpartContact === null, 'zero-confirm cancel hides contact');
  const free = await api('GET', `/api/items/${second.id}`, owner.token);check(!free.hasAcceptedClaim && free.version === 3, 'cancel releases item');
  await api('POST', `/api/items/${second.id}/claims`, applicant.token, { ...create, expectedItemVersion: free.version }, 409, 'CLAIM_EXISTS');
  await api('POST', `/api/claims/${waiting.id}/reject`, owner.token, { expectedVersion: 0, reason: 'Synthetic rejection' });
  await api('PUT', `/api/items/${second.id}`, owner.token, { ...content, title: 'Edited snapshot item', expectedVersion: free.version });
  check((await api('GET', `/api/claims/${c.id}`, applicant.token)).item.title === content.title, 'old snapshot survives edit');
  stage = 'admin exception resolution and sanitized logs';
  const third = await item();let d = await apply(third, applicant);const rejectedLater = await apply(third, other);
  d = await api('POST', `/api/claims/${d.id}/accept`, owner.token, { expectedVersion: 0, contact: 'Synthetic publisher' });
  await api('POST', `/api/admin/claims/${d.id}/resolve`, admin.token, { expectedVersion: d.version, action: 'TERMINATE', conclusion: 'Synthetic', reason: 'Synthetic' }, 409, 'STATE_CONFLICT');
  d = await api('POST', `/api/claims/${d.id}/confirm-handover`, owner.token);await revoke(applicant);
  await api('POST', `/api/claims/${d.id}/confirm-handover`, owner.token, undefined, 409, 'COUNTERPART_INELIGIBLE');
  await api('POST', `/api/claims/${d.id}/confirm-receipt`, applicant.token, undefined, 403, 'VERIFICATION_REQUIRED');
  const resolution = { expectedVersion: d.version, action: 'CONTINUE', conclusion: 'PRIVATE_CONCLUSION', reason: 'Synthetic waiting', internalNote: 'PRIVATE_NOTE' };
  d = detail(await api('POST', `/api/admin/claims/${d.id}/resolve`, admin.token, resolution), true);check(d.status === 'ACCEPTED' && !d.receivedAt && d.latestResolution.action === 'CONTINUE', 'continue never substitutes confirmation');
  detail(await api('GET', `/api/claims/${d.id}`, owner.token));
  d = detail(await api('POST', `/api/admin/claims/${d.id}/resolve`, admin.token, { ...resolution, expectedVersion: d.version, action: 'TERMINATE', reason: 'Synthetic termination' }), true);
  check(d.status === 'CANCELLED' && d.handedOverAt && !d.receivedAt && !d.completedAt, 'exception termination retains single confirmation');
  check((await api('GET', `/api/claims/${rejectedLater.id}`, other.token)).status === 'REJECTED', 'termination rejects others');
  const logs = await api('GET', `/api/admin/logs?objectType=CLAIM&objectId=${d.id}&pageSize=50`, admin.token);
  for (const log of logs.records) { keys(log, ['id', 'objectType', 'objectId', 'action', 'actorId', 'occurredAt', 'beforeState', 'afterState', 'reason']);check(log.objectId === d.id && log.objectType === 'CLAIM', 'precise object filter'); }
  check(!/PRIVATE_NOTE|PRIVATE_CONCLUSION|PRIVATE_EVIDENCE|CONTACT/.test(JSON.stringify(logs)), 'generic logs omit sensitive fields');
  await api('GET', '/api/admin/logs', owner.token, undefined, 403, 'FORBIDDEN');await api('GET', `/api/admin/logs?objectId=${d.id}`, admin.token, undefined, 400);
  const adminPage = await api('GET', `/api/admin/claims?itemId=${third.id}&applicantId=${applicant.userId}`, admin.token);check(adminPage.total === 1 && adminPage.records[0].id === d.id, 'admin filters');keys(adminPage.records[0], summaryKeys);
  stage = 'completed idempotency despite opponent revocation';
  const afterRevoke = detail(await api('POST', `/api/claims/${a.id}/confirm-handover`, owner.token));check(afterRevoke.version === a.version, 'completed retry does not require opponent eligibility');
  stage = 'ordinary admin takedown and unavailable publisher';
  const fourth = await item();let e = await apply(fourth, other);e = await api('POST', `/api/claims/${e.id}/accept`, owner.token, { expectedVersion: 0, contact: 'Synthetic' });
  await api('POST', `/api/admin/items/${fourth.id}/close`, admin.token, { expectedVersion: 2, reason: 'Synthetic ordinary takedown' });check((await api('GET', `/api/claims/${e.id}`, other.token)).status === 'CANCELLED', 'zero-confirm admin cascade');
  const fifth = await item();await revoke(owner);check((await apply(fifth, other)).status === 'APPLIED', 'creation only needs applicant eligibility');
  console.log(`PASS ${checks} claim/handover HTTP assertions. Synthetic users ${owner.userId},${applicant.userId},${other.userId},${unverified.userId}; items ${fixtures.join(',')}. No records deleted.`);
} catch (error) { console.error(`FAIL ${stage}: ${error.message}`); process.exitCode = 1; }
finally { for (const token of sessions) { try { await api('POST', '/api/auth/logout', token); } catch { console.error('Own session logout not confirmed'); process.exitCode = 1; } } }
