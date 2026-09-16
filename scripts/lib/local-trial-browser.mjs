// Local Edge/CDP helper for the explicitly confirmed model trial; never attaches to a user's browser.
import { spawn } from 'node:child_process';
import { mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
export const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

export async function openTrialBrowser(origin) {
  if (origin !== 'http://127.0.0.1:15176') throw new Error('Only the isolated local model-trial frontend is allowed');
  const profile = await mkdtemp(join(tmpdir(), 'bishe-live-ai-'));
  const browser = spawn('C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', [
    '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
    '--remote-debugging-port=0', '--remote-debugging-address=127.0.0.1', `--user-data-dir=${profile}`, 'about:blank',
  ], { windowsHide: true, stdio: 'ignore' });
  let socket, launchError;
  browser.on('error', error => { launchError = error; });
  const failures = [], responses = [], networkFailures = [], requestPaths = new Map(), paused = [], pending = new Map();
  let sequence = 0, exceptions = 0, externalRequests = 0;
  try {
    let port;
    for (let i = 0; i < 150; i++) {
      if (launchError) throw new Error('Dedicated Edge trial process could not start');
      try { port = (await readFile(join(profile, 'DevToolsActivePort'), 'utf8')).split('\n')[0]; break; } catch { await delay(100); }
    }
    if (!port || !/^\d+$/.test(port)) throw new Error('Dedicated Edge debugger did not become ready');
    const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`, { signal: AbortSignal.timeout(5000) })).json();
    const target = targets.find(t => t.type === 'page');
    if (!target?.webSocketDebuggerUrl?.startsWith(`ws://127.0.0.1:${port}/`)) throw new Error('Unexpected browser target');
    socket = new WebSocket(target.webSocketDebuggerUrl);
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('Browser debugger connection timeout')), 5000);
      socket.addEventListener('open', () => { clearTimeout(timer); resolve(); }, { once: true });
      socket.addEventListener('error', () => { clearTimeout(timer); reject(new Error('Browser debugger connection failed')); }, { once: true });
    });
    function send(method, params = {}) {
      return new Promise((resolve, reject) => {
        const id = ++sequence;
        const timeout = setTimeout(() => { pending.delete(id); reject(new Error(`Browser command timeout: ${method}`)); }, 35000);
        pending.set(id, { resolve: value => { clearTimeout(timeout); resolve(value); }, reject: () => { clearTimeout(timeout); reject(new Error(`Browser command failed: ${method}`)); } });
        socket.send(JSON.stringify({ id, method, params }));
      });
    }
    socket.addEventListener('message', event => {
      const message = JSON.parse(event.data);
      if (message.id) { const call = pending.get(message.id); if (call) { pending.delete(message.id); message.error ? call.reject() : call.resolve(message.result); } }
      if (message.method === 'Fetch.requestPaused') paused.push(message.params);
      if (message.method === 'Runtime.exceptionThrown') exceptions++;
      if (message.method === 'Network.requestWillBeSent') {
        const url = message.params.request.url;
        if (/^https?:/.test(url) && new URL(url).origin !== origin) externalRequests++;
        if (url.startsWith(origin + '/')) requestPaths.set(message.params.requestId, new URL(url).pathname);
      }
      if (message.method === 'Network.loadingFailed') {
        const path = requestPaths.get(message.params.requestId);
        if (path) networkFailures.push({ path, error: message.params.errorText, canceled: Boolean(message.params.canceled) });
        requestPaths.delete(message.params.requestId);
      }
      if (message.method === 'Network.loadingFinished') requestPaths.delete(message.params.requestId);
      if (message.method === 'Network.responseReceived') {
        const { url, status, mimeType } = message.params.response;
        if (url.startsWith(origin + '/api/') || url.startsWith(origin + '/assets/')) responses.push({ path: new URL(url).pathname, status, mimeType });
      }
    });
    async function evaluate(expression) {
      const result = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
      if (result.exceptionDetails) throw new Error('Browser evaluation failed; private expression details withheld');
      return result.result.value;
    }
    async function until(expression, label, failureExpression) {
      for (let i = 0; i < 350; i++) {
        if (await evaluate(expression)) return;
        if (failureExpression) {
          const failure = await evaluate(failureExpression);
          if (failure) throw new Error(`Browser terminal failure: ${label}: ${String(failure).slice(0, 500)}`);
        }
        await delay(100);
      }
      throw new Error(`Browser state not reached: ${label}`);
    }
    async function check(expression, label) {
      if (!await evaluate(expression)) { failures.push(label); throw new Error(`Browser assertion failed: ${label}`); }
    }
    async function navigate(path) {
      if (!path.startsWith('/') || path.startsWith('//')) throw new Error('Relative local route required');
      await send('Page.navigate', { url: origin + path });
      await until("document.readyState==='complete' && !!document.querySelector('main')", path);
    }
    async function fill(selector, value) {
      await evaluate(`(() => { const e = document.querySelector(${JSON.stringify(selector)}); e.value = ${JSON.stringify(value)}; e.dispatchEvent(new Event('input', {bubbles:true})); e.dispatchEvent(new Event('change', {bubbles:true})); })()`);
    }
    async function click(selector) { await evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`); }
    async function nextPaused() {
      for (let i = 0; i < 350; i++) { if (paused.length) return paused.shift(); await delay(100); }
      throw new Error('Expected controlled request/response delay not observed');
    }
    async function screenshot(path, width = 1440, height = 1000) {
      await send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: width < 600 });
      await evaluate('document.fonts.ready.then(()=>true)'); await delay(150);
      await check('document.documentElement.scrollWidth <= innerWidth + 1', 'no horizontal overflow');
      const picture = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true });
      await writeFile(path, Buffer.from(picture.data, 'base64'));
    }
    await send('Page.enable'); await send('Runtime.enable'); await send('Network.enable');
    return { send, evaluate, until, check, navigate, fill, click, nextPaused, screenshot,
      diagnostics: () => ({ exceptions, externalRequests, responses, failures, networkFailures }),
      close: () => { for (const call of pending.values()) call.reject(); pending.clear(); socket.close(); browser.kill(); },
    };
  } catch (error) { socket?.close(); browser.kill(); throw error; }
}
