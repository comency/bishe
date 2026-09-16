// Actual HTTP admission/disabled/validation/rate tests; never claims a real model has been tested.
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
if (process.argv[2] === '--help') { console.log('TEST_ADMIN_PASSWORD required. node scripts/check-ai-api.mjs --confirm-test-environment. Fixed 127.0.0.1:18080, aiEnabled=false, synthetic user. No model calls or deletion.'); process.exit(0); }
if (process.argv.length !== 3 || process.argv[2] !== '--confirm-test-environment' || !process.env.TEST_ADMIN_PASSWORD) throw new Error('Explicit isolated test confirmation required');
const base = 'http://127.0.0.1:18080', sessions = [];
let checks = 0;
function check(ok, label) { assert.ok(ok, label); checks++; }
async function api(method, path, token, body, expected = 200, code) {
  const response = await fetch(base + path, { method, redirect: 'error', signal: AbortSignal.timeout(15000), headers: { ...(token ? { 'X-Token': token } : {}), ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}) }, body: body === undefined ? undefined : JSON.stringify(body) });
  check(response.status === expected, `${method} ${path}: expected ${expected}, got ${response.status}`);
  check(response.headers.get('cache-control')?.includes('no-store'), 'private no-store');
  const envelope = await response.json();check(envelope.code === (expected === 200 ? 0 : -1), 'status matches envelope');
  if (code) check(envelope.errorCode === code, `expected ${code}`);
  if (expected === 429) check(response.headers.get('retry-after') === '60', 'rate window exposed');
  return envelope.data;
}
try {
  const config = await api('GET', '/api/public/config');check(config.isTest === true && config.aiEnabled === false, 'isolated disabled-model environment');
  const credentials = { username: `ai_${randomBytes(7).toString('hex')}`, password: randomBytes(16).toString('hex') };
  await api('POST', '/api/auth/register', null, { ...credentials, nickname: 'AI合成测试' });
  const user = await api('POST', '/api/auth/login', null, credentials);sessions.push(user.token);
  const admin = await api('POST', '/api/auth/login', null, { username: 'admin', password: process.env.TEST_ADMIN_PASSWORD });sessions.push(admin.token);
  await api('POST', '/api/ai/chat', undefined, { question: '如何认领？' }, 401, 'AUTH_REQUIRED');
  await api('POST', '/api/ai/polish', user.token, { content: 'Synthetic' }, 403, 'VERIFICATION_REQUIRED');
  await api('POST', '/api/ai/chat', admin.token, { question: 'Synthetic' }, 403, 'VERIFICATION_REQUIRED');
  await api('POST', '/api/verifications/me', user.token, { expectedVersion: 0, realName: '合成AI测试同学', statement: 'Synthetic only' });
  let qualification = await api('GET', `/api/admin/verifications/${user.userId}`, admin.token);
  qualification = await api('POST', `/api/admin/verifications/${user.userId}/review`, admin.token, { applicationId: qualification.currentApplication.id, expectedVersion: qualification.summary.version, decision: 'APPROVED', method: 'IN_PERSON', evidenceSummary: 'Synthetic only', validThrough: new Date(Date.now() + 3 * 86400000).toISOString().slice(0, 10) });
  for (const [path, body] of [['polish', { content: '图书馆捡到蓝色水杯，合成测试。' }], ['chat', { question: '双方如何确认归还？' }]]) {
    const result = await api('POST', `/api/ai/${path}`, user.token, body);
    check(Object.keys(result).sort().join(',') === 'content,reason,status', 'exact AI response whitelist');
    check(result.status === 'UNAVAILABLE' && result.reason === 'DISABLED' && typeof result.content === 'string', 'disabled is not generated');
    check(!/AI_API_KEY|127\.0\.0\.1|11434|application\.yml|Bearer|api\.openai/.test(result.content), 'no internal configuration or credentials in fallback');
  }
  await api('POST', '/api/ai/polish', user.token, {}, 400, 'VALIDATION_ERROR');
  await api('POST', '/api/ai/chat', user.token, { question: ' ' }, 400, 'VALIDATION_ERROR');
  await api('POST', '/api/ai/polish', user.token, { content: 'x'.repeat(3001) }, 400, 'VALIDATION_ERROR');
  await api('POST', '/api/ai/chat', user.token, { question: 'Synthetic', privateEvidence: 'MUST_NOT_BIND' }, 400, 'VALIDATION_ERROR');
  await api('POST', '/api/ai/polish', user.token, { content: 'Synthetic' }, 429, 'RATE_LIMITED');
  check((await api('GET', '/api/items/mine/page', user.token)).total === 0, 'AI never creates items and business still usable after rate limit');
  await api('POST', `/api/admin/verifications/${user.userId}/revoke`, admin.token, { expectedVersion: qualification.summary.version, reason: 'Synthetic old-token check' });
  await api('POST', '/api/ai/chat', user.token, { question: 'Synthetic' }, 403, 'VERIFICATION_REQUIRED');
  console.log(`PASS ${checks} AI HTTP assertions. Synthetic user ${user.userId}. Model generation NOT evaluated; no records deleted.`);
} catch (error) { console.error(error.message);process.exitCode = 1; }
finally { for (const token of sessions) { try { await api('POST', '/api/auth/logout', token); } catch { console.error('Own session logout not confirmed');process.exitCode = 1; } } }
