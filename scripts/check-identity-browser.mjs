// Real Edge + Vue + isolated API checks. Creates only uniquely marked synthetic test accounts.
import { spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { mkdtemp, readFile, mkdir, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';

if (process.argv[2] === '--help') {
  console.log('Start integration backend :18080 and npm run dev:integration (:15174). Set TEST_ADMIN_PASSWORD; run node scripts/check-identity-browser.mjs --confirm-test-environment. Only synthetic test data; no cleanup of database/Redis.');
  process.exit(0);
}
const includeItems = process.argv[3] === '--items';
if (process.argv.length !== (includeItems ? 4 : 3) || process.argv[2] !== '--confirm-test-environment' || !process.env.TEST_ADMIN_PASSWORD) {
  console.error('Explicit test-environment confirmation and TEST_ADMIN_PASSWORD required; use --help.');
  process.exit(1);
}
const origin = 'http://127.0.0.1:15174';
const config = await (await fetch(`${origin}/api/public/config`)).json();
if (config.data?.isTest !== true) throw new Error('Refusing non-test environment.');
const output = resolve(includeItems ? '.local/items-browser' : '.local/identity-browser');
await mkdir(output, { recursive: true });
const profile = await mkdtemp(join(tmpdir(), 'bishe-identity-browser-'));
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

try {
  for (let i = 0; i < 100; i++) {
    try { port = (await readFile(join(profile, 'DevToolsActivePort'), 'utf8')).split('\n')[0]; break; }
    catch { await delay(100); }
  }
  if (!port) throw new Error('Local Edge did not start.');
  const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
  const user = await page(targets.find(target => target.type === 'page'));
  const adminTarget = await (await fetch(`http://127.0.0.1:${port}/json/new?about:blank`, { method: 'PUT' })).json();
  const admin = await page(adminTarget);
  const username = `browser_${Date.now()}`;
  const password = `Test-${randomBytes(12).toString('hex')}`;
  await user.navigate('/items');
  await user.until("location.pathname==='/login' && !!document.querySelector('#login-password')", 'anonymous login redirect');
  await user.screenshot('login-desktop');
  await user.navigate('/register');
  await user.until("!!document.querySelector('#register-username')", 'register form');
  await user.fill('#register-username', username); await user.fill('#register-nickname', '浏览器合成同学');
  await user.fill('#register-password', password); await user.fill('#register-confirmation', password);
  await user.click('.auth-form button[type="submit"]');
  await user.until("location.pathname==='/login'", 'registration complete');
  await user.login(username, password);
  await user.until("location.pathname==='/verification' && !!document.querySelector('#verification-name')", 'unverified redirected');
  await user.check('unverified account has no hall navigation', "!document.querySelector('nav a[href=\"/items\"]')");
  await user.screenshot('verification-desktop'); await user.screenshot('verification-mobile', 375, 900);
  await user.navigate('/profile');
  await user.until("!!document.querySelector('#profile-contact')", 'profile form');
  await user.fill('#profile-nickname', '浏览器合成同学（更新）'); await user.fill('#profile-contact', 'synthetic-browser-contact');
  await user.click('.auth-form button[type="submit"]');
  await user.until("document.body.innerText.includes('本人资料已保存')", 'profile save');
  await user.navigate('/verification');
  await user.until("!!document.querySelector('#verification-name')", 'verification form');
  await user.fill('#verification-name', '浏览器合成申请人');
  await user.click('.auth-form button[type="submit"]');
  await user.until("!!document.querySelector('.status-badge.pending')", 'pending application');
  const userId = await user.evaluate("JSON.parse(sessionStorage.getItem('campus-lost-found.session.v1')).userId");
  await admin.login('admin', process.env.TEST_ADMIN_PASSWORD);
  await admin.until("location.pathname==='/admin/verifications'", 'admin management entry');
  await admin.navigate(`/admin/verifications/${userId}`);
  await admin.until("!!document.querySelector('#review-decision')", 'admin review form');
  await admin.fill('#review-decision', 'REJECTED');
  await admin.fill('#review-reason', '测试：请重新核对账号对应关系');
  await admin.fill('#review-note', 'PRIVATE_BROWSER_NOTE');
  await admin.click('.auth-form button[type="submit"]');
  await admin.until("!!document.querySelector('.status-badge.rejected')", 'rejection saved');
  await user.navigate('/verification');
  await user.until("!!document.querySelector('.status-badge.rejected')", 'self rejection shown');
  await user.check('self result hides internal note', "!document.body.innerText.includes('PRIVATE_BROWSER_NOTE')");
  await user.fill('#verification-name', '浏览器合成申请人');
  await user.click('.auth-form button[type="submit"]');
  await user.until("!!document.querySelector('.status-badge.pending')", 'resubmitted application');
  await admin.navigate(`/admin/verifications/${userId}`);
  await admin.until("!!document.querySelector('#review-evidence')", 'new current application');
  await admin.fill('#review-evidence', 'PRIVATE_BROWSER_EVIDENCE：仅合成材料演示，本人与账号及在校关系测试');
  const tomorrow = new Date(Date.now() + 86400000).toLocaleDateString('en-CA', { timeZone: 'Asia/Shanghai' });
  await admin.fill('#review-until', tomorrow);
  await admin.click('.checkbox-label input');
  await admin.screenshot('review-desktop'); await admin.screenshot('review-mobile', 375, 1000);
  await admin.click('.auth-form button[type="submit"]');
  await admin.until("!!document.querySelector('.status-badge.verified')", 'approval saved');
  await user.navigate('/verification');
  await user.until("!!document.querySelector('.status-badge.verified')", 'self approval shown');
  await user.check('self history hides evidence', "!document.body.innerText.includes('PRIVATE_BROWSER_EVIDENCE')");
  await user.navigate('/items');
  await user.until("!!document.querySelector('.items-page') && !document.querySelector('.spinner')", 'eligible hall access');
  await user.screenshot('eligible-hall-mobile', 375, 900);
  if (includeItems) {
    stage = 'item publication and private picture';
    await user.navigate('/items/new');
    await user.until("!!document.querySelector('.item-form textarea')", 'item form');
    await user.fill('.item-form input[maxlength="100"]', '浏览器测试蓝色水杯');
    await user.fill('.item-form textarea', '合成物品，仅用于自动化测试，无真实个人信息。');
    await user.evaluate(`(() => {
      const raw=atob('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jJ1kAAAAASUVORK5CYII=');
      const file=new File([Uint8Array.from(raw,c=>c.charCodeAt(0))],'synthetic.png',{type:'image/png'});
      const data=new DataTransfer();data.items.add(file);const input=document.querySelector('input[type=file]');input.files=data.files;input.dispatchEvent(new Event('change',{bubbles:true}));
    })()`);
    await user.until("!!document.querySelector('.private-image') && !document.querySelector('.item-form fieldset').disabled", 'private upload preview');
    await user.screenshot('item-publish-mobile', 375, 900);
    await user.click('.item-form button[type="submit"]');
    await user.until("/^\\/items\\/\\d+$/.test(location.pathname) && !!document.querySelector('.item-article')", 'published pending item');
    const itemId = await user.evaluate("Number(location.pathname.split('/').pop())");
    await user.check('new item awaits review', "document.querySelector('.item-article').innerText.includes('待审核')");
    await user.until("!!document.querySelector('.private-image')", 'bound image after submission');
    await user.check('image uses revocable Blob URL rather than public storage', "document.querySelector('.private-image').src.startsWith('blob:')");
    await admin.navigate(`/admin/items/${itemId}`);
    await admin.until("!!document.querySelector('.item-form select')", 'item review form');
    await admin.fill('.item-form select', 'REJECTED');
    await admin.fill('.item-form textarea', '浏览器测试：请补充颜色');
    // Each textarea has its own label; fill the internal-note textarea explicitly.
    await admin.evaluate("(() => { const e=document.querySelectorAll('.item-form')[0].querySelectorAll('textarea')[1];e.value='PRIVATE_ITEM_BROWSER_NOTE';e.dispatchEvent(new Event('input',{bubbles:true}));})()");
    await admin.click('.item-form input[type="checkbox"]');
    await admin.screenshot('item-review-desktop', 1440, 1000);
    await admin.click('.item-form button[type="submit"]');
    await admin.until("document.querySelector('.item-article').innerText.includes('已驳回')", 'item rejection');
    await user.navigate(`/items/${itemId}`);
    await user.until("!!document.querySelector('.item-article')", 'owner rejection detail');
    await user.check('item owner cannot see internal item note', "!document.body.innerText.includes('PRIVATE_ITEM_BROWSER_NOTE')");
    await user.navigate(`/items/${itemId}/edit`);
    await user.until("!!document.querySelector('.item-form textarea') && document.querySelector('.item-form textarea').value.length>0", 'loaded editable item');
    await user.fill('.item-form textarea', '合成物品：蓝色水杯，编辑后需要再次审核。');
    await user.click('.item-form button[type="submit"]');
    await user.until("/^\\/items\\/\\d+$/.test(location.pathname) && !!document.querySelector('.item-article')", 'resubmission');
    await user.check('edited item requires review again', "document.querySelector('.item-article').innerText.includes('待审核')");
    await admin.navigate(`/admin/items/${itemId}`);
    await admin.until("!!document.querySelector('.item-form select')", 'new content review');
    await admin.click('.item-form input[type="checkbox"]');
    await admin.click('.item-form button[type="submit"]');
    await admin.until("document.querySelector('.item-article').innerText.includes('已公开')", 'approval');
    await user.navigate('/items');
    await user.until("!!document.querySelector('.search-panel')", 'hall search');
    await user.fill('.search-panel input[type="search"]', '浏览器测试蓝色水杯');
    await user.click('.search-panel button[type="submit"]');
    await user.until(`!!document.querySelector('a[href="/items/${itemId}"]')`, 'approved item in hall');
    await user.screenshot('items-hall-desktop', 1440, 1000);
    await user.navigate(`/items/${itemId}`);
    await user.until("!!document.querySelector('.item-form textarea')", 'owner close form');
    await user.screenshot('item-detail-mobile', 375, 1000);
    await user.fill('.item-form textarea', '浏览器合成测试结束');
    await user.click('.item-form input[type="checkbox"]');
    await user.click('.item-form button[type="submit"]');
    await user.until("document.querySelector('.item-article').innerText.includes('已关闭')", 'owner closure');
    await user.check('closed item has no edit/close form', "!document.querySelector('.item-form') && !document.querySelector('a[href$=\"/edit\"]')");
    await user.navigate('/items/mine');
    await user.until(`!!document.querySelector('a[href="/items/${itemId}"]')`, 'closed item retained in my publications');
    await user.screenshot('items-mine-mobile', 375, 1000);
    stage = 'late write response after item navigation';
    const navigationItems = await user.evaluate(`(async()=>{
      const session=JSON.parse(sessionStorage.getItem('campus-lost-found.session.v1'));
      const ids=[];
      for(const title of ['导航竞态合成物品A','导航竞态合成物品B']){
        const response=await fetch('/api/items',{method:'POST',headers:{'X-Token':session.token,'Content-Type':'application/json'},body:JSON.stringify({title,description:'仅用于迟到响应隔离测试',type:'LOST'})});
        if(!response.ok)throw new Error('fixture creation failed');ids.push((await response.json()).data.id);
      }return ids;
    })()`);
    await user.navigate(`/items/${navigationItems[0]}`);
    await user.until("!!document.querySelector('.item-form textarea')", 'navigation fixture close form');
    await user.evaluate(`(() => {
      const original=window.fetch.bind(window);window.__heldItemResponse=false;
      window.fetch=async (...args)=>{
        const response=await original(...args);
        if(args[0]===${JSON.stringify(`/api/items/${navigationItems[0]}/close`)}){
          window.__heldItemResponse=true;await new Promise(resolve=>{window.__releaseItemResponse=resolve});
        }return response;
      };
      window.__restoreItemFetch=()=>{window.fetch=original};
    })()`);
    await user.fill('.item-form textarea', '合成导航竞态测试');
    await user.click('.item-form input[type="checkbox"]');
    await user.click('.item-form button[type="submit"]');
    await user.until('window.__heldItemResponse===true', 'real close committed but response delivery held');
    await user.evaluate(`document.querySelector('#app').__vue_app__.config.globalProperties.$router.push('/items/${navigationItems[1]}')`);
    await user.until("document.querySelector('.item-article')?.innerText.includes('导航竞态合成物品B')", 'new detail must replace pending old detail');
    await user.evaluate('window.__releaseItemResponse();window.__restoreItemFetch()');
    await delay(200);
    await user.check('late write response cannot populate a different item route', "document.querySelector('.item-article').innerText.includes('导航竞态合成物品B') && !document.querySelector('.item-article').innerText.includes('导航竞态合成物品A') && !document.querySelector('.item-form fieldset').disabled");
    await user.navigate('/items');
    await user.until("!!document.querySelector('.search-panel')", 'hall before revocation');
    await admin.navigate(`/admin/verifications/${userId}`);
    await admin.until("!!document.querySelector('#review-reason')", 'identity management restored');
    checks.push('real item upload/create/reject/edit/approve/public paging/close/private Blob preview');
  }
  stage = 'revoke approved qualification';
  await admin.fill('#review-reason', '浏览器测试撤销');
  await admin.click('.auth-form button[type="submit"]');
  await admin.until("!!document.querySelector('.status-badge.revoked')", 'revocation saved');
  stage = 'business request with revoked token';
  // Use the old session on an actual business request; the UI must handle the real 403.
  await user.evaluate("document.querySelector('.search-panel').requestSubmit()");
  await user.until("location.pathname==='/verification' && !!document.querySelector('.status-badge.revoked')", 'revoked old token redirect');
  await user.check('revocation removes stale business content', "!document.querySelector('.items-page') && !document.querySelector('#verification-name')");
  stage = 'reopen qualification';
  await admin.fill('#review-reason', '测试核实后允许重新申请');
  await admin.click('.auth-form button[type="submit"]');
  await admin.until("!!document.querySelector('.status-badge.unverified')", 'reopen saved');
  await user.navigate('/verification');
  await user.until("!!document.querySelector('#verification-name')", 'reopen permits new application');
  await user.check('reopen preserves original approved history', "!!document.querySelector('.history-surface .status-badge.verified') && !!document.querySelector('.history-surface .status-badge.rejected')");
  stage = 'invalidate browser session';
  await user.evaluate("(async()=>{const session=JSON.parse(sessionStorage.getItem('campus-lost-found.session.v1'));await fetch('/api/auth/logout',{method:'POST',headers:{'X-Token':session.token}})})()");
  await user.navigate('/profile');
  await user.until("location.pathname==='/login'", 'expired session login redirect');
  await user.check('401 clears stored token', "sessionStorage.getItem('campus-lost-found.session.v1')===null");
  await admin.click('.account button');
  await admin.until("location.pathname==='/login'", 'admin logout');
  if (exceptions || externalRequests) throw new Error('Unexpected browser exception or external HTTP request.');
  if (!responses.some(response => response.path === '/api/items/page' && response.status === 403)) throw new Error('No real qualification 403 observed.');
  checks.push('real registration/profile/rejection/resubmission/approval/revocation/reopen/logout', 'zero uncaught exceptions or external HTTP requests');
  await writeFile(join(output, 'result.json'), JSON.stringify({ checks, responses, browserExceptions: exceptions, externalRequests }, null, 2));
  console.log(`PASS: ${checks.length} real-browser assertions; responsive screenshots in ${output}.`);
} catch (error) {
  console.error(`${stage}: ${error instanceof Error ? error.message : 'Identity browser check failed.'}`);
  console.error(JSON.stringify(responses.slice(-8)));
  process.exitCode = 1;
} finally {
  for (const activePage of pages) {
    try {
      await activePage.evaluate(`(async()=>{if(location.origin!==${JSON.stringify(origin)})return;const s=JSON.parse(sessionStorage.getItem('campus-lost-found.session.v1')||'null');if(s){await fetch('/api/auth/logout',{method:'POST',headers:{'X-Token':s.token},signal:AbortSignal.timeout(3000)});sessionStorage.clear()}})()`);
    } catch { console.error('Browser session cleanup was not confirmed.'); }
  }
  // Only this isolated browser is stopped; database records remain for inspection.
  for (const socket of sockets) socket.close();
  browser.kill();
}
