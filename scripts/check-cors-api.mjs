// No credentials or fixture mutations. Tests only fixed, explicitly enabled loopback trial API.
import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
if (process.argv[2] === '--help') {
  console.log('node scripts/check-cors-api.mjs --confirm-local-trial. Requires guarded API :18081. No account/model/database mutations; checks exact CORS origins and anonymous authorization.'); process.exit(0);
}
if (process.argv.length !== 3 || process.argv[2] !== '--confirm-local-trial') throw new Error('Explicit --confirm-local-trial required before HTTP checks');
const base = 'http://127.0.0.1:18081', allowed = 'http://127.0.0.1:15176';
const output = resolve('.local/cors-trial', new Date().toISOString().replace(/[:.]/g, '-'));
await mkdir(output, { recursive: true });
const checks = [], responses = [];
let failure;
function check(value, label) { assert.ok(value, label); checks.push(label); }
async function request(path, init = {}) {
  const response = await fetch(base + path, { ...init, redirect: 'error', signal: AbortSignal.timeout(5000) });
  responses.push({ path, method: init.method ?? 'GET', status: response.status, allowOrigin: response.headers.get('access-control-allow-origin') });
  return response;
}
try {
  const config = await (await request('/api/public/config')).json();
  check(config.data?.isTest === true && config.data?.aiEnabled === true, 'explicit enabled test profile');
  const preflight = await request('/api/ai/chat', { method: 'OPTIONS', headers: { Origin: allowed, 'Access-Control-Request-Method': 'POST', 'Access-Control-Request-Headers': 'content-type,x-token' } });
  check(preflight.status === 200 && preflight.headers.get('access-control-allow-origin') === allowed, 'exact local trial origin accepted');
  check(!preflight.headers.has('access-control-allow-credentials'), 'no cookie credential sharing');
  for (const origin of ['https://unrelated.example', 'http://127.0.0.1:15175', 'http://127.0.0.1:15174', 'null']) {
    const pre = await request('/api/ai/chat', { method: 'OPTIONS', headers: { Origin: origin, 'Access-Control-Request-Method': 'POST' } });
    check(pre.status === 403 && !pre.headers.has('access-control-allow-origin'), `untrusted preflight denied: ${origin}`);
    const actual = await request('/api/public/config', { headers: { Origin: origin } });
    check(actual.status === 403 && !actual.headers.has('access-control-allow-origin'), `untrusted actual request denied: ${origin}`);
  }
  const same = await request('/api/public/config', { headers: { Origin: base } });
  check(same.status === 200, 'same-origin remains usable');
  const anonymous = await request('/api/ai/chat', { method: 'POST', headers: { Origin: allowed, 'Content-Type': 'application/json' }, body: JSON.stringify({ question: 'Synthetic anonymous boundary' }) });
  check(anonymous.status === 401 && (await anonymous.json()).errorCode === 'AUTH_REQUIRED', 'allowed origin never replaces authentication');
} catch (error) { failure = error.message; process.exitCode = 1; console.error(failure); }
finally {
  await writeFile(resolve(output, 'result.json'), JSON.stringify({ checks, responses, failure: failure ?? null, fixtureMutations: 0, generatedModelCalls: 0,
    limitation: 'Local HTTP CORS only; no HTTPS deployment or proxy trust assertion' }, null, 2));
  console.log(`${failure ? 'FAIL' : 'PASS'} ${checks.length} actual CORS HTTP checks. Evidence: ${output}`);
}
