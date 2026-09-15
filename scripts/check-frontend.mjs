// Real-browser baseline check. Uses only local Edge, frontend and the dev administrator.
// No registration, item writes or external browser services; credentials are not logged.
import { spawn } from 'node:child_process';
import { mkdtemp, readFile, mkdir, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';

if (process.argv[2] === '--help') {
  console.log('Set ADMIN_PASSWORD, start dev backend :8080 and frontend :5174, then run node scripts/check-frontend.mjs --confirm-local-development. Screenshots go to .local/browser-check/.');
  process.exit(0);
}
if (process.argv.length !== 3 || process.argv[2] !== '--confirm-local-development' || !process.env.ADMIN_PASSWORD) {
  console.error('Explicit local-development confirmation and ADMIN_PASSWORD are required. Use --help.');
  process.exit(1);
}
const output = resolve('.local/browser-check');
await mkdir(output, { recursive: true });
const profile = await mkdtemp(join(tmpdir(), 'bishe-baseline-browser-'));
const browser = spawn('C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', [
  '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
  '--remote-debugging-port=0', '--remote-debugging-address=127.0.0.1',
  `--user-data-dir=${profile}`, 'about:blank',
], { windowsHide: true, stdio: 'ignore' });
let socket;
let sequence = 0;
const pending = new Map();
const checks = [];
const exceptions = [];
const external = [];
const responses = [];
const delay = ms => new Promise(r => setTimeout(r, ms));
function send(method, params = {}) {
  return new Promise((accept, reject) => {
    const id = ++sequence;
    const timeout = setTimeout(() => { pending.delete(id); reject(new Error(`Browser command timed out: ${method}`)); }, 12000);
    pending.set(id, { accept: result => { clearTimeout(timeout); accept(result); }, reject: () => { clearTimeout(timeout); reject(new Error(`Browser command failed: ${method}`)); } });
    socket.send(JSON.stringify({ id, method, params }));
  });
}
async function evaluate(expression) {
  const result = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (result.exceptionDetails) throw new Error('Browser evaluation failed (details withheld).');
  return result.result.value;
}
async function check(label, expression) {
  if (!await evaluate(expression)) throw new Error(`Failed: ${label}`);
  checks.push(label);
}
async function until(expression) {
  for (let attempt = 0; attempt < 80; attempt++) {
    if (await evaluate(expression)) return;
    await delay(150);
  }
  throw new Error('Expected browser state did not appear.');
}
async function navigate(path) {
  await send('Page.navigate', { url: `http://127.0.0.1:5174${path}` });
  await until("document.readyState === 'complete' && !!document.querySelector('main')");
}
async function reload() {
  const previousDocument = await evaluate('performance.timeOrigin');
  await send('Page.reload');
  await until(`performance.timeOrigin !== ${previousDocument} && document.readyState === 'complete' && !!document.querySelector('main')`);
}
async function screenshot(name, width, height) {
  await send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: width < 600 });
  await delay(200);
  await check(`${name}: no horizontal overflow`, 'document.documentElement.scrollWidth <= innerWidth + 1');
  const result = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true });
  await writeFile(join(output, `${name}.png`), Buffer.from(result.data, 'base64'));
}
async function login(password) {
  await evaluate(`(() => {
    const user = document.querySelector('#login-username');
    const pass = document.querySelector('#login-password');
    user.value = 'admin'; user.dispatchEvent(new Event('input', {bubbles:true}));
    pass.value = ${JSON.stringify(password)}; pass.dispatchEvent(new Event('input', {bubbles:true}));
    document.querySelector('.auth-form').requestSubmit();
  })()`);
}
const sessionKey = 'campus-lost-found.session.v1';
try {
  let port;
  for (let i = 0; i < 80; i++) {
    try { port = (await readFile(join(profile, 'DevToolsActivePort'), 'utf8')).split('\n')[0]; break; } catch { await delay(100); }
  }
  if (!port) throw new Error('Local Edge did not start.');
  const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
  socket = new WebSocket(targets.find(t => t.type === 'page').webSocketDebuggerUrl);
  await new Promise((accept, reject) => { socket.addEventListener('open', accept, { once:true }); socket.addEventListener('error', reject, { once:true }); });
  socket.addEventListener('message', event => {
    const message = JSON.parse(event.data);
    if (message.id) { const call = pending.get(message.id); if (call) { pending.delete(message.id); message.error ? call.reject() : call.accept(message.result); } }
    if (message.method === 'Runtime.exceptionThrown') exceptions.push('uncaught browser exception');
    if (message.method === 'Network.requestWillBeSent') {
      const url = message.params.request.url;
      if (/^https?:/.test(url) && new URL(url).origin !== 'http://127.0.0.1:5174') external.push('external request');
    }
    if (message.method === 'Network.responseReceived') {
      const { url, status } = message.params.response;
      if (url.startsWith('http://127.0.0.1:5174/api/')) responses.push({ path: new URL(url).pathname, status });
    }
  });
  await send('Page.enable'); await send('Runtime.enable'); await send('Network.enable');
  await navigate('/items');
  await until("location.pathname === '/login' && !!document.querySelector('#login-password')");
  await check('anonymous business navigation requires login', "location.pathname === '/login'");
  await screenshot('login-desktop', 1440, 1000);
  await screenshot('login-mobile', 375, 812);
  await login('invalid-baseline-password');
  await until("!!document.querySelector('.error-message')");
  await check('real invalid credentials show an error without navigation', "location.pathname === '/login' && document.querySelector('.error-message').textContent.length > 0");
  await login(process.env.ADMIN_PASSWORD);
  await until("location.pathname === '/items' && !!document.querySelector('.state-panel h3') && !document.querySelector('.spinner')");
  await check('real login and proxy load the empty dev hall', "!!document.querySelector('.items-page') && !document.querySelector('.error-state') && document.querySelector('.state-panel h3').textContent.includes('没有找到')");
  await check('password is not stored in sessionStorage', `!JSON.stringify({...sessionStorage}).includes(${JSON.stringify(process.env.ADMIN_PASSWORD)})`);
  await screenshot('hall-desktop', 1440, 1000);
  await screenshot('hall-mobile', 375, 812);
  const queriesBeforeRefresh = responses.filter(r => r.path === '/api/items').length;
  await reload();
  await until("location.pathname === '/items' && !!document.querySelector('.state-panel h3') && !document.querySelector('.spinner')");
  await check('refresh retains session and revalidates through protected API', "!!document.querySelector('.items-page') && !document.querySelector('.error-state')");
  if (responses.filter(r => r.path === '/api/items').length <= queriesBeforeRefresh) throw new Error('Refresh did not issue a new protected API request.');
  checks.push('refresh issued a new real protected API request');
  // Invalidate only this browser's session, then let a real HTTP 401 drive the UI.
  await evaluate(`(async()=>{const session=JSON.parse(sessionStorage.getItem('${sessionKey}'));const r=await fetch('/api/auth/logout',{method:'POST',headers:{'X-Token':session.token}});if(!r.ok)throw Error('logout failed')})()`);
  await reload();
  await until("location.pathname === '/login' && !!document.querySelector('#login-password')");
  await check('real 401 clears local token and returns to login', `sessionStorage.getItem('${sessionKey}') === null`);
  await login(process.env.ADMIN_PASSWORD);
  await until("location.pathname === '/items' && !!document.querySelector('.account button')");
  await evaluate("document.querySelector('.account button').click()");
  await until("location.pathname === '/login' && !!document.querySelector('#login-password')");
  await check('UI logout clears session', `sessionStorage.getItem('${sessionKey}') === null`);
  await navigate('/missing-baseline-page');
  await until("!!document.querySelector('.not-found')");
  await check('404 route has a usable fallback', "!!document.querySelector('.not-found a')");
  if (exceptions.length || external.length) throw new Error('Unexpected browser exception or external request.');
  if (!responses.some(r => r.path === '/api/auth/login' && r.status === 400) || !responses.some(r => r.path === '/api/items' && r.status === 401)) throw new Error('Expected real error responses were not observed.');
  checks.push('no uncaught browser exceptions or external HTTP requests', 'real login HTTP 400 and protected HTTP 401 observed');
  await writeFile(join(output, 'result.json'), JSON.stringify({ checks, responses, externalRequests: external.length, browserExceptions: exceptions.length }, null, 2));
  console.log(`PASS: ${checks.length} real-browser assertions; four responsive screenshots saved to .local/browser-check/.`);
} catch (error) {
  console.error(error instanceof Error ? error.message : 'Browser check failed.'); process.exitCode = 1;
} finally {
  if (socket?.readyState === WebSocket.OPEN) {
    try { await evaluate(`(async()=>{if(location.origin!=='http://127.0.0.1:5174')return;const s=JSON.parse(sessionStorage.getItem('${sessionKey}')||'null');if(s){await fetch('/api/auth/logout',{method:'POST',headers:{'X-Token':s.token},signal:AbortSignal.timeout(3000)});sessionStorage.clear()}})()`); } catch { console.error('Browser session cleanup was not confirmed.'); }
    socket.close();
  }
  browser.kill();
}
