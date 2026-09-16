// Real Edge + Vue + isolated API checks. Creates only uniquely marked synthetic test accounts.
import { spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { mkdtemp, readFile, mkdir, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';

if (process.argv[2] === '--help') {
  console.log('Start integration backend :18080 and npm run dev:integration (:15174). Set TEST_ADMIN_PASSWORD; run node scripts/check-ai-browser.mjs --confirm-test-environment. Only synthetic test data; no cleanup of database/Redis.');
  process.exit(0);
}
if (process.argv.length !== 3 || process.argv[2] !== '--confirm-test-environment' || !process.env.TEST_ADMIN_PASSWORD) {
  console.error('Explicit test-environment confirmation and TEST_ADMIN_PASSWORD required; use --help.');
  process.exit(1);
}
const origin = 'http://127.0.0.1:15174';
const config = await (await fetch(`${origin}/api/public/config`)).json();
if (config.data?.isTest !== true) throw new Error('Refusing non-test environment.');
const output = resolve('.local/ai-browser');
await mkdir(output, { recursive: true });
const profile = await mkdtemp(join(tmpdir(), 'bishe-ai-browser-'));
const browser = spawn('C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', [
  '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
  '--remote-debugging-port=0', '--remote-debugging-address=127.0.0.1',
  `--user-data-dir=${profile}`, 'about:blank',
], { windowsHide: true, stdio: 'ignore' });
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const checks = [];
const sockets = [];
const pages = [];
const responses = [];
let exceptions = 0;
let externalRequests = 0;
let port;
let stage = 'startup';
async function page(target) {
  const socket = new WebSocket(target.webSocketDebuggerUrl);
  sockets.push(socket);
  await new Promise((resolve, reject) => { socket.addEventListener('open', resolve, { once: true }); socket.addEventListener('error', reject, { once: true }); });
  let sequence = 0;
  const pending = new Map();
  const intercepted = [];
  function send(method, params = {}) {
    return new Promise((resolve, reject) => {
      const id = ++sequence;
      const timeout = setTimeout(() => { pending.delete(id); reject(new Error(`Browser command timed out: ${method}`)); }, method === 'Page.captureScreenshot' ? 30000 : 15000);
      pending.set(id, { resolve: result => { clearTimeout(timeout); resolve(result); }, reject: () => { clearTimeout(timeout); reject(new Error(`Browser command failed: ${method}`)); } });
      socket.send(JSON.stringify({ id, method, params }));
    });
  }
  socket.addEventListener('message', event => {
    const message = JSON.parse(event.data);
    if (message.id) {
      const call = pending.get(message.id);
      if (call) { pending.delete(message.id); message.error ? call.reject() : call.resolve(message.result); }
    }
    if (message.method === 'Fetch.requestPaused') intercepted.push(message.params);
    if (message.method === 'Runtime.exceptionThrown') exceptions++;
    if (message.method === 'Network.requestWillBeSent') {
      const url = message.params.request.url;
      if (/^https?:/.test(url) && new URL(url).origin !== origin) externalRequests++;
    }
    if (message.method === 'Network.responseReceived') {
      const { url, status } = message.params.response;
      if (url.startsWith(`${origin}/api/`)) responses.push({ path: new URL(url).pathname, status });
    }
  });
  async function evaluate(expression) {
    const result = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (result.exceptionDetails) throw new Error('Browser evaluation failed; private details withheld.');
    return result.result.value;
  }
  async function until(expression, label) {
    for (let i = 0; i < 100; i++) { if (await evaluate(expression)) return; await delay(100); }
    throw new Error(`Expected browser state missing: ${label}`);
  }
  async function navigate(path) {
    await send('Page.navigate', { url: origin + path });
    await until("document.readyState==='complete' && !!document.querySelector('main')", path);
  }
  async function fill(selector, value) {
    await evaluate(`(() => { const element = document.querySelector(${JSON.stringify(selector)}); element.value = ${JSON.stringify(value)}; element.dispatchEvent(new Event('input', {bubbles:true})); element.dispatchEvent(new Event('change', {bubbles:true})); })()`);
  }
  async function click(selector) { await evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`); }
  async function check(label, expression) {
    if (!await evaluate(expression)) throw new Error(`Browser assertion failed: ${label}`);
    checks.push(label);
  }
  async function screenshot(name, width = 1440, height = 1000) {
    await send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: width < 600 });
    await delay(150);
    await check(`${name}: no horizontal overflow`, 'document.documentElement.scrollWidth <= innerWidth + 1');
    await evaluate('document.fonts.ready.then(()=>true)');
    const image = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true });
    await writeFile(join(output, `${name}.png`), Buffer.from(image.data, 'base64'));
  }
  async function login(username, password) {
    await navigate('/login');
    await until("!!document.querySelector('#login-password')", 'login form');
    await fill('#login-username', username); await fill('#login-password', password);
    await click('.auth-form button[type="submit"]');
  }
  await send('Page.enable'); await send('Runtime.enable'); await send('Network.enable');
  const result = { send, evaluate, until, navigate, fill, click, check, screenshot, login, intercepted };
  pages.push(result);
  return result;
}

const apiSessions = [];
async function api(method, path, token, body) {
  const response = await fetch(origin + path, { method, redirect: 'error', signal: AbortSignal.timeout(15000), headers: { ...(token ? { 'X-Token': token } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) }, body: body ? JSON.stringify(body) : undefined });
  if (!response.ok) throw new Error(`Fixture API ${path} failed: ${response.status}`);
  return (await response.json()).data;
}
try {
  for (let i = 0; i < 100; i++) {
    try { port = (await readFile(join(profile, 'DevToolsActivePort'), 'utf8')).split('\n')[0]; break; } catch { await delay(100); }
  }
  if (!port) throw new Error('Local Edge did not start.');
  const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
  const user = await page(targets.find(target => target.type === 'page'));
  const manager = await api('POST', '/api/auth/login', null, { username: 'admin', password: process.env.TEST_ADMIN_PASSWORD });apiSessions.push(manager.token);
  const credentials = { username: `aiui_${randomBytes(6).toString('hex')}`, password: randomBytes(16).toString('hex') };
  await api('POST', '/api/auth/register', null, { ...credentials, nickname: '合成智能辅助测试' });
  const account = await api('POST', '/api/auth/login', null, credentials);apiSessions.push(account.token);
  await api('POST', '/api/verifications/me', account.token, { expectedVersion: 0, realName: '合成AI测试同学', statement: 'Synthetic only' });
  let qualification = await api('GET', `/api/admin/verifications/${account.userId}`, manager.token);
  qualification = await api('POST', `/api/admin/verifications/${account.userId}/review`, manager.token, { applicationId: qualification.currentApplication.id, expectedVersion: qualification.summary.version, decision: 'APPROVED', method: 'IN_PERSON', evidenceSummary: 'Synthetic only', validThrough: new Date(Date.now() + 3 * 86400000).toISOString().slice(0, 10) });
  stage = 'real disabled backend and static guide';
  await user.login(credentials.username, credentials.password);await user.until("location.pathname==='/items'", 'verified login');
  await user.navigate('/assistant');await user.until("!!document.querySelector('.assistant-page textarea')", 'assistant form');
  await user.fill('.assistant-page textarea', '双方如何确认归还？');await user.click('.assistant-page button[type=submit]');
  await user.until("document.body.innerText.includes('这是降级提示')", 'real disabled response');
  await user.check('static guide usable with disabled model', "document.body.innerText.includes('静态使用说明') && document.body.innerText.includes('只有双方确认') && document.querySelector('textarea').value==='双方如何确认归还？'");
  await user.screenshot('assistant-disabled-desktop');await user.screenshot('assistant-disabled-mobile', 375, 900);
  stage = 'real disabled polish never overwrites input';
  await user.navigate('/items/new');await user.until("!!document.querySelector('.ai-panel')", 'polish component');
  await user.fill('.item-form input[maxlength="100"]', '合成测试水杯');
  await user.fill('.item-form textarea', '合成原文：图书馆蓝色水杯。');await user.click('.ai-panel button');
  await user.until("document.body.innerText.includes('此提示不是模型生成结果')", 'disabled preview');
  await user.check('disabled preserves input and manual submit', "document.querySelector('.item-form textarea').value==='合成原文：图书馆蓝色水杯。' && !document.querySelector('button[type=submit]').disabled && !document.body.innerText.includes('核对后采用到描述')");
  await user.screenshot('polish-disabled-mobile', 375, 900);
  stage = 'synthetic provider response: stale snapshot and escaped markup';
  await user.send('Fetch.enable', { patterns: [{ urlPattern: origin + '/api/ai/polish', requestStage: 'Request' }] });
  async function paused() { for (let i = 0; i < 100; i++) { if (user.intercepted.length) return user.intercepted.shift(); await delay(50); } throw new Error('Expected synthetic provider request'); }
  async function fulfill(request, content) {
    await user.send('Fetch.fulfillRequest', { requestId: request.requestId, responseCode: 200, responseHeaders: [{ name: 'Content-Type', value: 'application/json' }, { name: 'Cache-Control', value: 'no-store' }], body: Buffer.from(JSON.stringify({ code: 0, message: 'success', data: { content, status: 'GENERATED', reason: null } })).toString('base64') });
  }
  await user.click('.ai-panel button');let held = await paused();
  if (Object.keys(JSON.parse(held.request.postData)).join(',') !== 'content') throw new Error('Unexpected private AI request fields');
  checks.push('preview request sends only explicit description');
  await user.fill('.item-form textarea', '用户等待时修改后的新原文。');
  await fulfill(held, '<img src=x onerror="window.syntheticXss=true">合成建议（非真实模型）');
  await user.until("document.body.innerText.includes('原文已变化')", 'stale preview');
  await user.check('stale preview cannot replace current input', "document.querySelectorAll('.ai-panel button.secondary-button')[1].disabled && document.querySelector('.item-form textarea').value==='用户等待时修改后的新原文。'");
  await user.check('model markup is plain text not executable HTML', "!document.querySelector('.ai-panel img') && !window.syntheticXss && document.querySelector('.ai-preview').innerText.includes('<img')");
  await user.screenshot('polish-stale-synthetic-desktop', 1440, 1000);
  stage = 'synthetic suggestion requires explicit adoption and explicit publish';
  await user.click('.ai-panel button');held = await paused();await fulfill(held, '合成建议：图书馆的蓝色水杯，请通过认领流程核对。');
  await user.until("document.querySelector('.ai-preview')?.innerText.startsWith('合成建议：')", 'new preview');
  await user.check('generated response still does not change form', "document.querySelector('.item-form textarea').value==='用户等待时修改后的新原文。'");
  await user.evaluate("document.querySelectorAll('.ai-panel button.secondary-button')[1].click()");
  await user.check('adoption only changes description', "document.querySelector('.item-form textarea').value.startsWith('合成建议：') && location.pathname==='/items/new'");
  if ((await api('GET', '/api/items/mine/page', account.token)).total !== 0) throw new Error('AI unexpectedly created business data');
  checks.push('preview and adoption never submit business data');
  stage = 'late response discarded when route unmounts';
  await user.click('.ai-panel button');held = await paused();await user.click('nav a[href="/assistant"]');
  await user.until("location.pathname==='/assistant' && !!document.querySelector('.assistant-page')", 'leave pending preview');
  try { await fulfill(held, 'STALE_PRIVATE_RESULT'); } catch { /* Browser may already have cancelled this request on unmount. */ }
  await user.check('late result cannot restore former page', "!document.body.innerText.includes('STALE_PRIVATE_RESULT') && !document.querySelector('.ai-panel')");
  await user.send('Fetch.disable');
  stage = 'revocation removes assistant and denies old token';
  await api('POST', `/api/admin/verifications/${account.userId}/revoke`, manager.token, { expectedVersion: qualification.summary.version, reason: 'Synthetic AI revocation test' });
  await user.fill('.assistant-page textarea', 'Synthetic post-revocation question');await user.click('.assistant-page button[type=submit]');
  await user.until("location.pathname==='/verification'", 'real AI 403 redirects');
  await user.check('revoked session has no assistant content', "!document.querySelector('.assistant-page') && !document.body.innerText.includes('Synthetic post-revocation question')");
  if (exceptions || externalRequests) throw new Error('Unexpected browser exception or external HTTP request.');
  checks.push('real disabled backend, static guide and qualification revocation', 'generated preview cases use synthetic interception, NOT a model quality evaluation');
  await writeFile(join(output, 'result.json'), JSON.stringify({ checks, responses, browserExceptions: exceptions, externalRequests, modelEffectVerified: false }, null, 2));
  console.log(`PASS ${checks.length} AI browser assertions. Generated-preview cases are synthetic, model quality NOT evaluated. Screenshots: ${output}`);
} catch (error) { console.error(`${stage}: ${error.message}`);console.error(JSON.stringify(responses.slice(-8)));process.exitCode = 1; }
finally {
  for (const activePage of pages) {
    try { await activePage.evaluate(`(async()=>{if(location.origin!==${JSON.stringify(origin)})return;const s=JSON.parse(sessionStorage.getItem('campus-lost-found.session.v1')||'null');if(s){await fetch('/api/auth/logout',{method:'POST',headers:{'X-Token':s.token},signal:AbortSignal.timeout(3000)});sessionStorage.clear()}})()`); }
    catch { console.error('Browser session cleanup was not confirmed.'); }
  }
  for (const token of apiSessions) { try { await api('POST', '/api/auth/logout', token); } catch { console.error('Fixture logout not confirmed');process.exitCode = 1; } }
  for (const socket of sockets) socket.close();browser.kill();
}
