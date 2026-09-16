// Read-only business checks against the already unavailable dedicated test Redis.
// Never stops dependencies, creates accounts, changes configuration or calls the model.
import assert from 'node:assert/strict';
import { connect } from 'node:net';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { openTrialBrowser } from './lib/local-trial-browser.mjs';

if (process.argv[2] === '--help') {
  console.log('Requires isolated modeltrial API :18081/frontend :15176 and test Redis :16380 ALREADY stopped. No dependency is stopped by this script. node scripts/check-redis-outage.mjs --confirm-test-redis-unavailable');
  process.exit(0);
}
if (process.argv.length !== 3 || process.argv[2] !== '--confirm-test-redis-unavailable') throw new Error('Explicit --confirm-test-redis-unavailable required before network access');
await new Promise((resolve, reject) => {
  const socket = connect({ host: '127.0.0.1', port: 16380 });
  socket.setTimeout(1500);
  socket.once('connect', () => { socket.destroy(); reject(new Error('Test Redis is listening; refuse outage scenario')); });
  socket.once('error', error => error.code === 'ECONNREFUSED' ? resolve() : reject(new Error('Redis outage precondition is ambiguous')));
  socket.once('timeout', () => { socket.destroy(); reject(new Error('Cannot establish Redis outage precondition')); });
});
const origin = 'http://127.0.0.1:15176', output = resolve('.local/redis-outage', new Date().toISOString().replace(/[:.]/g, '-'));
await mkdir(output, { recursive: true });
const checks = [], responses = [];
let page, failure;
function check(value, label) { assert.ok(value, label); checks.push(label); }
async function request(method, path, body, token) {
  const started = performance.now();
  const response = await fetch(origin + path, { method, redirect: 'error', signal: AbortSignal.timeout(10000),
    headers: { ...(body ? { 'Content-Type': 'application/json' } : {}), ...(token ? { 'X-Token': token } : {}) }, body: body ? JSON.stringify(body) : undefined });
  const data = await response.json();
  responses.push({ method, path, status: response.status, elapsedMs: Math.round(performance.now() - started), errorCode: data.errorCode });
  check(response.headers.get('cache-control')?.includes('no-store'), `${path}: no-store`);
  return { response, data };
}
try {
  const { response, data } = await request('GET', '/api/public/config');
  check(response.status === 200 && data.data?.isTest === true && data.data?.aiEnabled === true, 'explicit local test profile public configuration survives outage');
  for (const [method, path, body, token] of [
    ['POST', '/api/auth/login', { username: 'outage_synthetic_only', password: 'Synthetic-password-only' }],
    ['GET', '/api/users/me', undefined, 'outage-synthetic-token'],
    ['GET', '/api/items/page', undefined, 'outage-synthetic-token'],
    ['POST', '/api/ai/chat', { question: 'Synthetic outage check' }, 'outage-synthetic-token'],
  ]) {
    const { response: failed, data: envelope } = await request(method, path, body, token);
    check(failed.status === 503 && envelope.code === -1 && envelope.errorCode === 'SERVICE_UNAVAILABLE' && envelope.data === null, `${path}: dependency error, never authentication success or AI fallback`);
    check(typeof envelope.traceId === 'string' && envelope.traceId.length > 0, `${path}: trace ID`);
    check(!/redis|localhost|16380|password|exception|jdbc|outage-synthetic/i.test(JSON.stringify(envelope)), `${path}: no dependency/credential details`);
  }
  const anonymous = await request('GET', '/api/items/page');
  check(anonymous.response.status === 401 && anonymous.data.errorCode === 'AUTH_REQUIRED', 'anonymous request remains unauthorized, no fail-open');
  page = await openTrialBrowser(origin);
  await page.navigate('/login'); await page.until("!!document.querySelector('#login-password')", 'login form');
  await page.fill('#login-username', 'outage_synthetic_only'); await page.fill('#login-password', 'Synthetic-password-only');
  await page.click('.auth-form button[type=submit]');
  await page.until("document.body.innerText.includes('服务暂时不可用')", 'clear dependency unavailable message');
  await page.check("location.pathname==='/login' && !sessionStorage.getItem('campus-lost-found.session.v1')", 'failed login creates no session or business access');
  checks.push('real browser displays service unavailable and stays signed out');
  await page.fill('#login-password', '');
  await page.screenshot(join(output, 'redis-unavailable-desktop.png'));
  await page.screenshot(join(output, 'redis-unavailable-mobile.png'), 375, 900);
  const diagnostics = page.diagnostics();
  check(diagnostics.exceptions === 0 && diagnostics.externalRequests === 0, 'no browser exceptions or external HTTP');
} catch (error) { failure = error.message; process.exitCode = 1; console.error(failure); }
finally {
  const diagnostics = page?.diagnostics(); page?.close();
  await writeFile(join(output, 'result.json'), JSON.stringify({ at: new Date().toISOString(), checks, responses, diagnostics, failure: failure ?? null,
    generatedModelCalls: 0, createdAccounts: 0, limitation: 'Redis outage only; does not prove successful login, model generation, dependency recovery or database outage behavior' }, null, 2));
  console.log(`${failure ? 'FAIL' : 'PASS'} ${checks.length} real Redis-outage checks. Evidence: ${output}`);
}
