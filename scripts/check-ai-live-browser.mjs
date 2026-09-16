// Actual browser + guarded API + local model. Delays real traffic only; NEVER fabricates model responses.
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { freemem } from 'node:os';
import { mkdir, writeFile, readFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { delay, openTrialBrowser } from './lib/local-trial-browser.mjs';

if (process.argv[2] === '--help') {
  console.log('Requires explicit modeltrial API :18081, frontend :15176, verified Ollama :11434 and TEST_ADMIN_PASSWORD. node scripts/check-ai-live-browser.mjs --confirm-local-model-trial --confirm-test-environment [--wait-for-model-idle]. Optional paced-functional mode waits at most 15 seconds before each model request for unload and 4.25 GiB free, never retries requests; not rapid-use performance evidence. Only synthetic accounts. Real traffic can be delayed for stale-result checks, never replaced.');
  process.exit(0);
}
if (![4, 5].includes(process.argv.length) || (process.argv.length === 5 && process.argv[4] !== '--wait-for-model-idle') || process.argv[2] !== '--confirm-local-model-trial' || process.argv[3] !== '--confirm-test-environment' || !process.env.TEST_ADMIN_PASSWORD) {
  throw new Error('Both explicit confirmations and TEST_ADMIN_PASSWORD required before browser/network access');
}
const origin = 'http://127.0.0.1:15176', provider = 'http://127.0.0.1:11434';
const output = resolve('.local/ai-live-browser', new Date().toISOString().replace(/[:.]/g, '-'));
const policy = JSON.parse(await readFile('src/main/resources/ai-content-policy.json', 'utf8'));
await mkdir(output, { recursive: true });
const checks = [], sessions = [], acceptedTexts = [], admissionWaits = [];
const paced = process.argv[4] === '--wait-for-model-idle';
let page, failure, failureUi, stage = 'preflight', syntheticUserId, ownsModelTrial = false;
function check(value, label) { assert.ok(value, label); checks.push(label); }
async function api(method, path, token, body, expected = 200) {
  const response = await fetch(origin + path, { method, redirect: 'error', signal: AbortSignal.timeout(30000),
    headers: { ...(token ? { 'X-Token': token } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) }, body: body ? JSON.stringify(body) : undefined });
  assert.equal(response.status, expected, `${method} ${path}: expected ${expected}, got ${response.status}`);
  const envelope = await response.json(); assert.equal(envelope.code, expected === 200 ? 0 : -1);
  return envelope.data;
}
async function modelApi(path, body) {
  const response = await fetch(provider + path, { method: body ? 'POST' : 'GET', redirect: 'error', signal: AbortSignal.timeout(10000),
    headers: { 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : undefined });
  assert.equal(response.status, 200); return response.json();
}
async function browserCheck(expression, label) { await page.check(expression, label); checks.push(label); }
async function waitBeforeGeneration(label) {
  if (!paced) return;
  // Explicit paced functional mode only: never retry a business request, unload a
  // model, or change the application's 4 GiB gate. Preserve rapid-mode failures.
  const start = performance.now();
  let stableSince;
  while (performance.now() - start < 15000) {
    const freeBytes = freemem();
    if (freeBytes >= 4.25 * 1024 ** 3 && (await modelApi('/api/ps')).models.length === 0) {
      stableSince ??= performance.now();
      if (performance.now() - stableSince >= 1000) {
        admissionWaits.push({ label, elapsedMs: Math.round(performance.now() - start), freeMiB: Math.round(freeBytes / 1024 ** 2) });
        return;
      }
    } else stableSince = undefined;
    await delay(250);
  }
  admissionWaits.push({ label, elapsedMs: Math.round(performance.now() - start), failed: true });
  throw new Error(`Paced pre-request readiness not reached: ${label}; no business request retried`);
}
try {
  check(freemem() >= 4 * 1024 ** 3, '4 GiB free before browser startup');
  const config = await api('GET', '/api/public/config');
  check(config.isTest === true && config.aiEnabled === true, 'explicit enabled test frontend');
  const admin = await api('POST', '/api/auth/login', null, { username: 'admin', password: process.env.TEST_ADMIN_PASSWORD }); sessions.push(admin.token);
  check((await api('GET', '/api/admin/ai-trial', admin.token)).localTrial === true, 'guarded trial marker before fixture mutation');
  check((await modelApi('/api/version')).version === '0.34.1', 'verified runtime');
  check((await modelApi('/api/ps')).models.length === 0, 'no preexisting loaded model');
  check((await modelApi('/api/tags')).models.some(m => m.name === 'qwen3:1.7b' && m.digest === '8f68893c685c3ddff2aa3fffce2aa60a30bb2da65ca488b61fff134a4d1730e7'), 'pinned trial model');
  ownsModelTrial = true;
  const credentials = { username: `liveui_${randomBytes(6).toString('hex')}`, password: randomBytes(18).toString('hex') };
  await api('POST', '/api/auth/register', null, { ...credentials, nickname: '真实模型合成浏览器测试' });
  const account = await api('POST', '/api/auth/login', null, credentials); sessions.push(account.token); syntheticUserId = account.userId;
  await api('POST', '/api/verifications/me', account.token, { expectedVersion: 0, realName: '合成模型测试同学', statement: 'Synthetic only' });
  let qualification = await api('GET', `/api/admin/verifications/${account.userId}`, admin.token);
  qualification = await api('POST', `/api/admin/verifications/${account.userId}/review`, admin.token, {
    applicationId: qualification.currentApplication.id, expectedVersion: qualification.summary.version, decision: 'APPROVED',
    method: 'IN_PERSON', evidenceSummary: 'Synthetic only', validThrough: new Date(Date.now() + 3 * 86400000).toISOString().slice(0, 10),
  });
  page = await openTrialBrowser(origin);
  await page.navigate('/login'); await page.until("!!document.querySelector('#login-password')", 'login form');
  await page.fill('#login-username', credentials.username); await page.fill('#login-password', credentials.password);
  await page.click('.auth-form button[type=submit]'); await page.until("location.pathname==='/items'", 'verified login', "document.querySelector('.auth-form .error-message')?.innerText");

  stage = 'real model guidance';
  await page.navigate('/assistant'); await page.until("!!document.querySelector('.assistant-page textarea')", 'assistant form');
  await page.fill('.assistant-page textarea', '我只想认领别人捡到的钥匙，也得先发一个启事吗？');
  await waitBeforeGeneration('guidance'); await page.click('.assistant-page button[type=submit]');
  await page.until("!!document.querySelector('.assistant-page .item-full-description')", 'actual model reply', "document.querySelector('.assistant-page .error-message')?.innerText");
  const guidance = await page.evaluate("document.querySelector('.assistant-page .item-full-description').innerText");
  check(guidance === policy.guideStatements[2], 'actual relevant guide selection displayed'); acceptedTexts.push({ kind: 'chat', content: guidance });
  await browserCheck("document.body.innerText.includes('模型选取、系统校验后展示') && document.body.innerText.includes('静态使用说明')", 'model selection distinguished from static help');
  await page.screenshot(join(output, 'real-guidance-desktop.png'));
  await page.screenshot(join(output, 'real-guidance-mobile.png'), 375, 900);

  stage = 'real preview and explicit adoption';
  await page.navigate('/items/new'); await page.until("!!document.querySelector('.ai-panel')", 'polish form');
  await page.fill('.item-form input[maxlength="100"]', '合成蓝色水杯');
  const original = '图书馆 捡到蓝色水杯，杯底有划痕';
  await page.fill('.item-form textarea', original);
  await waitBeforeGeneration('preview'); await page.click('.ai-panel button');
  await page.until("!!document.querySelector('.ai-preview')", 'real polish suggestion', "document.querySelector('.ai-panel .environment-notice, .ai-panel .error-message')?.innerText");
  const polished = await page.evaluate("document.querySelector('.ai-preview').innerText");
  check(polished.replace(/[，。\s]/g, '') === original.replace(/[，。\s]/g, ''), 'real polish retains facts and order'); acceptedTexts.push({ kind: 'polish', content: polished });
  await browserCheck(`document.querySelector('.item-form textarea').value===${JSON.stringify(original)}`, 'preview never automatically changes input');
  await page.screenshot(join(output, 'real-preview-desktop.png'), 1440, 1000);
  await page.evaluate("[...document.querySelectorAll('.ai-panel button')].find(b=>b.textContent.includes('核对后采用')).click()");
  await browserCheck(`document.querySelector('.item-form textarea').value===${JSON.stringify(polished)} && location.pathname==='/items/new'`, 'explicit adoption changes only description');
  check((await api('GET', '/api/items/mine/page', account.token)).total === 0, 'preview and adoption never publish');

  stage = 'stale input with delayed real request';
  await waitBeforeGeneration('stale preview');
  await page.send('Fetch.enable', { patterns: ['Request', 'Response'].map(requestStage => ({ urlPattern: origin + '/api/ai/polish', requestStage })) });
  await page.click('.ai-panel button');
  const heldRequest = await page.nextPaused();
  check(Object.keys(JSON.parse(heldRequest.request.postData)).join(',') === 'content', 'provider-bound request contains description only');
  await page.fill('.item-form textarea', '用户等待时改成新的合成描述。');
  await page.send('Fetch.continueRequest', { requestId: heldRequest.requestId });
  const staleResponse = await page.nextPaused();
  check(staleResponse.responseStatusCode === 200, 'stale real response observed');
  const staleBody = await page.send('Fetch.getResponseBody', { requestId: staleResponse.requestId });
  const staleReply = JSON.parse(staleBody.base64Encoded ? Buffer.from(staleBody.body, 'base64').toString('utf8') : staleBody.body);
  check(staleReply.data?.status === 'GENERATED', `stale response genuinely generated (status=${staleReply.data?.status}, reason=${staleReply.data?.reason})`);
  acceptedTexts.push({ kind: 'stale-real-polish', content: staleReply.data.content });
  await page.send('Fetch.continueRequest', { requestId: staleResponse.requestId });
  await page.until("document.body.innerText.includes('原文已变化')", 'stale actual preview');
  await browserCheck("[...document.querySelectorAll('.ai-panel button')].find(b=>b.textContent.includes('核对后采用')).disabled && document.querySelector('.item-form textarea').value==='用户等待时改成新的合成描述。'", 'old real result cannot overwrite newer input');
  await page.screenshot(join(output, 'real-stale-preview-mobile.png'), 375, 900);
  await page.send('Fetch.disable');

  stage = 'qualification change while a genuine response is held for delivery';
  await page.fill('.item-form textarea', '操场捡到红色钥匙扣，没有挂件。');
  await waitBeforeGeneration('qualification delivery boundary');
  await page.send('Fetch.enable', { patterns: [{ urlPattern: origin + '/api/ai/polish', requestStage: 'Response' }] });
  await page.click('.ai-panel button'); const heldResponse = await page.nextPaused();
  check(heldResponse.responseStatusCode === 200, 'real completed response observed before delivery delay');
  const body = await page.send('Fetch.getResponseBody', { requestId: heldResponse.requestId });
  const actualReply = JSON.parse(body.base64Encoded ? Buffer.from(body.body, 'base64').toString('utf8') : body.body);
  check(actualReply.data?.status === 'GENERATED', 'held response is genuinely generated, never substituted');
  acceptedTexts.push({ kind: 'held-real-polish', content: actualReply.data.content });
  await api('POST', `/api/admin/verifications/${account.userId}/revoke`, admin.token, { expectedVersion: qualification.summary.version, reason: 'Synthetic real-response delivery boundary' });
  // Router navigation refreshes actual eligibility; do not claim revocation during model computation.
  await page.click('nav a[href="/assistant"]'); await page.until("location.pathname==='/verification'", 'revoked eligibility observed by router');
  try { await page.send('Fetch.continueRequest', { requestId: heldResponse.requestId }); } catch { /* Aborted by unmount before delivery is also correct. */ }
  await page.send('Fetch.disable');
  await browserCheck("!document.querySelector('.ai-panel') && !document.querySelector('.assistant-page')", 'late real response cannot revive revoked business page');
  await api('POST', '/api/ai/chat', account.token, { question: '旧token再试' }, 403);
  const diagnostics = page.diagnostics();
  check(diagnostics.exceptions === 0 && diagnostics.externalRequests === 0, 'no browser errors or external HTTP requests');
} catch (error) {
  failure = `${stage}: ${error.message}`; console.error(failure); process.exitCode = 1;
  if (page) {
    try { failureUi = await page.evaluate("(document.querySelector('.ai-panel') || document.querySelector('.assistant-page'))?.innerText.slice(0,3000) ?? null"); }
    catch { /* Preserve the primary failure without collecting private page contents. */ }
    try { await page.screenshot(join(output, 'failure.png')); } catch { /* Best-effort evidence only. */ }
  }
} finally {
  if (page) {
    try { await page.evaluate(`(async()=>{if(location.origin!==${JSON.stringify(origin)})return;const s=JSON.parse(sessionStorage.getItem('campus-lost-found.session.v1')||'null');if(s){await fetch('/api/auth/logout',{method:'POST',headers:{'X-Token':s.token},signal:AbortSignal.timeout(3000)});sessionStorage.clear()}})()`); }
    catch { failure ??= 'Browser session cleanup unconfirmed'; process.exitCode = 1; }
  }
  for (const token of sessions) { try { await api('POST', '/api/auth/logout', token); } catch { failure ??= 'Fixture session cleanup unconfirmed'; process.exitCode = 1; } }
  if (ownsModelTrial) { try { await modelApi('/api/generate', { model: 'qwen3:1.7b', keep_alive: 0 }); check((await modelApi('/api/ps')).models.length === 0, 'selected model unloaded'); } catch { failure ??= 'Model unload unconfirmed'; process.exitCode = 1; } }
  const diagnostics = page?.diagnostics(); page?.close();
  await writeFile(join(output, 'result.json'), JSON.stringify({ checks, diagnostics, acceptedTexts, syntheticUserId, failure: failure ?? null, failureUi: failureUi ?? null,
    browserOpened: Boolean(page), actualAcceptedTexts: acceptedTexts.length, completed: !failure,
    mode: paced ? 'paced-functional' : 'rapid-functional', admissionWaits,
    fabricatedProviderResponses: false, plannedControlledDelays: ['one real request before sending', 'one real generated response before browser delivery'],
    contentPolicyVersion: policy.version, limitation: 'Controlled delivery delay is not proof of revocation during model computation' }, null, 2));
  console.log(`${failure ? 'FAIL' : 'PASS'} ${checks.length} real-model browser checks. Evidence: ${output}. Synthetic records retained.`);
}
