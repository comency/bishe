// Real Edge + Vue + isolated API checks. Creates only uniquely marked synthetic test accounts.
import { spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { mkdtemp, readFile, mkdir, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';

if (process.argv[2] === '--help') {
  console.log('Start integration backend :18080 and npm run dev:integration (:15174). Set TEST_ADMIN_PASSWORD; run node scripts/check-claims-browser.mjs --confirm-test-environment. Only synthetic test data; no cleanup of database/Redis.');
  process.exit(0);
}
if (process.argv.length !== 3 || process.argv[2] !== '--confirm-test-environment' || !process.env.TEST_ADMIN_PASSWORD) {
  console.error('Explicit test-environment confirmation and TEST_ADMIN_PASSWORD required; use --help.');
  process.exit(1);
}
const origin = 'http://127.0.0.1:15174';
const config = await (await fetch(`${origin}/api/public/config`)).json();
if (config.data?.isTest !== true) throw new Error('Refusing non-test environment.');
const output = resolve('.local/claims-browser');
await mkdir(output, { recursive: true });
const profile = await mkdtemp(join(tmpdir(), 'bishe-claims-browser-'));
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
  const result = { send, evaluate, until, navigate, fill, click, check, screenshot, login };
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
    try { port = (await readFile(join(profile, 'DevToolsActivePort'), 'utf8')).split('\n')[0]; break; }
    catch { await delay(100); }
  }
  if (!port) throw new Error('Local Edge did not start.');
  const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
  const applicant = await page(targets.find(target => target.type === 'page'));
  const owner = await page(await (await fetch(`http://127.0.0.1:${port}/json/new?about:blank`, { method: 'PUT' })).json());
  const admin = await page(await (await fetch(`http://127.0.0.1:${port}/json/new?about:blank`, { method: 'PUT' })).json());
  stage = 'synthetic fixtures';
  const manager = await api('POST', '/api/auth/login', null, { username: 'admin', password: process.env.TEST_ADMIN_PASSWORD });apiSessions.push(manager.token);
  const suffix = randomBytes(6).toString('hex');
  async function user(n) {
    const credentials = { username: `claimui_${suffix}_${n}`, password: randomBytes(16).toString('hex') };
    await api('POST', '/api/auth/register', null, { ...credentials, nickname: `合成认领同学${n}` });
    const u = await api('POST', '/api/auth/login', null, credentials);apiSessions.push(u.token);
    await api('POST', '/api/verifications/me', u.token, { expectedVersion: 0, realName: '合成浏览器测试', statement: 'Synthetic only' });
    const v = await api('GET', `/api/admin/verifications/${u.userId}`, manager.token);
    await api('POST', `/api/admin/verifications/${u.userId}/review`, manager.token, { expectedVersion: v.summary.version, applicationId: v.currentApplication.id, decision: 'APPROVED', method: 'IN_PERSON', evidenceSummary: 'Synthetic only', validThrough: new Date(Date.now() + 3 * 86400000).toISOString().slice(0, 10) });
    return { ...u, ...credentials };
  }
  const publisher = await user(1), claimant = await user(2);
  async function item() {
    const i = await api('POST', '/api/items', publisher.token, { title: '合成测试：图书馆蓝色水杯', description: '用于认领交接浏览器联调，不含真实个人资料。', type: 'FOUND', location: '测试图书馆' });
    return api('PUT', `/api/admin/items/${i.id}/review`, manager.token, { expectedVersion: 0, status: 'APPROVED' });
  }
  const first = await item(), second = await item();
  await applicant.login(claimant.username, claimant.password);await applicant.until("location.pathname==='/items'", 'claimant login');
  await owner.login(publisher.username, publisher.password);await owner.until("location.pathname==='/items'", 'publisher login');
  await admin.login('admin', process.env.TEST_ADMIN_PASSWORD);await admin.until("location.pathname==='/admin/verifications'", 'manager login');
  stage = 'apply through Vue';
  await applicant.navigate(`/items/${first.id}`);await applicant.until("!!document.querySelector('.item-form textarea')", 'claim form');
  await applicant.fill('.item-form textarea', '合成特征：杯底标记 TEST，不含真实资料。');
  await applicant.fill('.item-form input:not([type=checkbox])', 'SYNTHETIC_APPLICANT_CONTACT');
  await applicant.click('.item-form input[type=checkbox]');await applicant.click('.item-form button');
  await applicant.until("/^\\/claims\\/\\d+$/.test(location.pathname) && document.body.innerText.includes('待处理')", 'applied claim detail');
  const id = await applicant.evaluate("Number(location.pathname.split('/').pop())");
  await applicant.check('applied contact is hidden', "!document.body.innerText.includes('SYNTHETIC_APPLICANT_CONTACT')");
  await applicant.screenshot('claim-applied-desktop');
  stage = 'accept through publisher Vue';
  await owner.navigate(`/claims/incoming?itemId=${first.id}`);await owner.until(`!!document.querySelector('a[href="/claims/${id}"]')`, 'incoming list');
  await owner.click(`a[href="/claims/${id}"]`);await owner.until("document.body.innerText.includes('接受本次认领')", 'accept form');
  await owner.fill('.item-form input:not([type=checkbox])', 'SYNTHETIC_PUBLISHER_CONTACT');
  await owner.click('.item-form input[type=checkbox]');
  await owner.check('accept and reject consent are independent', "document.querySelectorAll('.item-form input[type=checkbox]')[1].checked===false");
  await owner.click('.item-form button');await owner.until("document.body.innerText.includes('对方本次交接联系方式')", 'accepted contact');
  await owner.check('publisher sees only applicant contact', "document.body.innerText.includes('SYNTHETIC_APPLICANT_CONTACT') && !document.body.innerText.includes('SYNTHETIC_PUBLISHER_CONTACT')");
  await owner.screenshot('claim-accepted-desktop');await owner.screenshot('claim-accepted-mobile', 375, 900);
  stage = 'receipt then handover';
  await applicant.navigate(`/claims/${id}`);await applicant.until("document.body.innerText.includes('确认已收到物品')", 'receipt form');
  await applicant.evaluate("(()=>{const f=[...document.querySelectorAll('form')].find(f=>f.innerText.includes('确认已收到物品'));f.querySelector('input[type=checkbox]').click();f.querySelector('button').click()})()");
  await applicant.until("document.body.innerText.includes('当前仅有一方确认')", 'single-confirm waiting');
  await applicant.check('one confirmation cannot imply completed', "!document.body.innerText.includes('双方完成时间')");
  await applicant.screenshot('claim-one-confirm-mobile', 375, 900);
  await owner.navigate(`/claims/${id}`);await owner.until("document.body.innerText.includes('确认已交出物品')", 'handover form');
  await owner.click('.item-form input[type=checkbox]');await owner.click('.item-form button');
  await owner.until("document.body.innerText.includes('双方完成时间')", 'completed');
  await owner.check('completed hides contact and all mutation forms', "!document.querySelector('.item-form') && !document.body.innerText.includes('SYNTHETIC_APPLICANT_CONTACT')");
  await owner.screenshot('claim-completed-desktop', 1440, 1000);
  await applicant.navigate(`/claims/${id}`);await applicant.until("document.body.innerText.includes('双方完成时间')", 'claimant completed');
  await applicant.check('claimant completed contact hidden', "!document.body.innerText.includes('SYNTHETIC_PUBLISHER_CONTACT')");
  stage = 'single confirmation exception fixture';
  let pending = await api('POST', `/api/items/${second.id}/claims`, claimant.token, { expectedItemVersion: second.version, identification: '合成异常处置特征', contact: 'SYNTHETIC_APPLICANT_CONTACT' });
  pending = await api('POST', `/api/claims/${pending.id}/accept`, publisher.token, { expectedVersion: 0, contact: 'SYNTHETIC_PUBLISHER_CONTACT' });
  pending = await api('POST', `/api/claims/${pending.id}/confirm-handover`, publisher.token);
  const qualification = await api('GET', `/api/admin/verifications/${claimant.userId}`, manager.token);
  await api('POST', `/api/admin/verifications/${claimant.userId}/revoke`, manager.token, { expectedVersion: qualification.summary.version, reason: 'Synthetic test revocation' });
  await applicant.navigate(`/claims/${pending.id}`);await applicant.until("location.pathname==='/verification'", 'revoked applicant redirect');
  await applicant.check('revocation clears claim evidence and contact', "!document.body.innerText.includes('SYNTHETIC_PUBLISHER_CONTACT') && !document.body.innerText.includes('合成异常处置特征')");
  stage = 'manager continues then terminates through Vue';
  await admin.navigate(`/admin/claims/${pending.id}`);await admin.until("document.body.innerText.includes('单方交接异常处置')", 'resolution form');
  await admin.fill('.item-form textarea:nth-of-type(1)', 'PRIVATE_CONCLUSION');
  // Textareas are nested in separate labels; query all explicitly.
  await admin.evaluate("(()=>{const a=document.querySelectorAll('.item-form textarea');a[1].value='测试：等待重新核验';a[1].dispatchEvent(new Event('input',{bubbles:true}));a[2].value='PRIVATE_NOTE';a[2].dispatchEvent(new Event('input',{bubbles:true}))})()");
  await admin.click('.item-form input[type=checkbox]');await admin.click('.item-form button');
  await admin.until("document.body.innerText.includes('最近异常处置') && document.body.innerText.includes('PRIVATE_CONCLUSION')", 'continue saved');
  await admin.check('continue retains single confirmation without fake completion', "document.body.innerText.includes('当前仅有一方确认') && !document.body.innerText.includes('双方完成时间')");
  await owner.navigate(`/claims/${pending.id}`);await owner.until("document.body.innerText.includes('测试：等待重新核验')", 'participant reason');
  await owner.check('participant never sees private conclusion or note', "!/PRIVATE_NOTE|PRIVATE_CONCLUSION/.test(document.body.innerText)");
  await admin.fill('.item-form select', 'TERMINATE');await admin.fill('.item-form textarea', 'PRIVATE_CONCLUSION_TERMINATE');
  await admin.evaluate("(()=>{const a=document.querySelectorAll('.item-form textarea');a[1].value='测试：终止并下架';a[1].dispatchEvent(new Event('input',{bubbles:true}));a[2].value='PRIVATE_NOTE';a[2].dispatchEvent(new Event('input',{bubbles:true}))})()");
  await admin.click('.item-form input[type=checkbox]');await admin.click('.item-form button');
  await admin.until("!document.querySelector('.item-form') && document.body.innerText.includes('已取消')", 'exception terminated');
  await admin.check('termination does not forge receipt or completion', "document.body.innerText.includes('申请者确认收到：尚未确认') && !document.body.innerText.includes('双方完成时间')");
  await admin.screenshot('claim-admin-terminated-desktop');
  stage = 'sanitized audit UI';
  await admin.navigate('/admin/logs');await admin.until("!!document.querySelector('.item-filters select')", 'log filters');await admin.fill('.item-filters select', 'CLAIM');
  await admin.fill('.item-filters input[type=number]', String(pending.id));await admin.click('.item-filters button');
  await admin.until("document.body.innerText.includes('管理处置：异常终止')", 'filtered audit');
  await admin.check('generic audit excludes private information', "!/PRIVATE_NOTE|PRIVATE_CONCLUSION|SYNTHETIC_APPLICANT_CONTACT|SYNTHETIC_PUBLISHER_CONTACT/.test(document.body.innerText)");
  await admin.screenshot('claim-audit-mobile', 375, 900);
  if (exceptions || externalRequests) throw new Error('Unexpected browser exception or external HTTP request.');
  checks.push('real Vue application/acceptance/receipt/handover/completion/continue/termination/audit', 'zero uncaught exceptions or external HTTP requests');
  await writeFile(join(output, 'result.json'), JSON.stringify({ checks, responses, browserExceptions: exceptions, externalRequests }, null, 2));
  console.log(`PASS: ${checks.length} real-browser claim assertions; responsive screenshots in ${output}.`);
} catch (error) {
  console.error(`${stage}: ${error instanceof Error ? error.message : 'Claim browser check failed.'}`);console.error(JSON.stringify(responses.slice(-8)));process.exitCode = 1;
} finally {
  for (const activePage of pages) {
    try { await activePage.evaluate(`(async()=>{if(location.origin!==${JSON.stringify(origin)})return;const s=JSON.parse(sessionStorage.getItem('campus-lost-found.session.v1')||'null');if(s){await fetch('/api/auth/logout',{method:'POST',headers:{'X-Token':s.token},signal:AbortSignal.timeout(3000)});sessionStorage.clear()}})()`); }
    catch { console.error('Browser session cleanup was not confirmed.'); }
  }
  for (const token of apiSessions) { try { await api('POST', '/api/auth/logout', token); } catch { console.error('Fixture session logout not confirmed');process.exitCode = 1; } }
  for (const socket of sockets) socket.close();browser.kill();
}
