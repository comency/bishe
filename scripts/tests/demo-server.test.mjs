import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import path from 'node:path';
import os from 'node:os';
import { mkdtemp, mkdir, writeFile, symlink, rm } from 'node:fs/promises';
import { createDemoServer, parseDemoArguments, MAX_BODY_BYTES } from '../lib/demo-server.mjs';

const listen = server => new Promise((resolve, reject) => {
  server.once('error', reject);
  server.listen(0, '127.0.0.1', () => resolve(server.address().port));
});
const close = server => new Promise(resolve => { server.close(resolve); server.closeAllConnections(); });
function send(port, target, { method = 'GET', headers = {}, body, chunks } = {}) {
  return new Promise((resolve, reject) => {
    const request = http.request({ hostname: '127.0.0.1', port, path: target, method, headers }, response => {
      const parts = [];
      response.on('data', part => parts.push(part));
      response.on('end', () => resolve({ status: response.statusCode, headers: response.headers, body: Buffer.concat(parts) }));
      response.on('error', reject);
    });
    request.on('error', reject);
    if (chunks) for (const chunk of chunks) request.write(chunk);
    request.end(body);
  });
}
async function fixture(t, backendHandler) {
  const temporary = await mkdtemp(path.join(os.tmpdir(), 'bishe-demo-http-'));
  const directory = path.join(temporary, 'dist');
  await mkdir(path.join(directory, 'assets'), { recursive: true });
  await writeFile(path.join(directory, 'index.html'), '<!doctype html><title>合成演示</title><div id="app"></div>');
  await writeFile(path.join(directory, 'assets', 'index-Abc123xy.js'), 'console.log("synthetic");');
  await writeFile(path.join(directory, 'assets', 'style-Def456xy.css'), 'body{color:green}');
  await writeFile(path.join(directory, 'icon.svg'), '<svg xmlns="http://www.w3.org/2000/svg"/>');
  const backend = http.createServer(backendHandler ?? ((_request, response) => response.end('backend')));
  const backendPort = await listen(backend);
  const server = await createDemoServer({ directory, backendOrigin: `http://127.0.0.1:${backendPort}` });
  const port = await listen(server);
  t.after(async () => { await close(server); await close(backend); await rm(temporary, { recursive: true, force: true }); });
  return { temporary, directory, backend, port };
}

test('CLI requires explicit confirmation and absolute directory, without host/backend/port overrides', () => {
  const directory = path.resolve('frontend/dist');
  assert.deepEqual(parseDemoArguments(['--confirm-local-demo', '--directory', directory]), { directory });
  assert.deepEqual(parseDemoArguments(['--directory', directory, '--confirm-local-demo']), { directory });
  assert.deepEqual(parseDemoArguments(['--help']), { help: true });
  for (const args of [[], ['--directory', directory], ['--confirm-local-demo'],
    ['--confirm-local-demo', '--directory', 'relative/dist'],
    ['--confirm-local-demo', '--directory', directory, '--port', '8080'],
    ['--confirm-local-demo', '--directory', directory, '--backend', 'http://example.test'],
    ['--confirm-local-demo', '--directory', directory, '--host', '0.0.0.0'],
    ['--confirm-local-demo', '--confirm-local-demo', '--directory', directory]]) assert.throws(() => parseDemoArguments(args));
});

test('real HTTP serves SPA routes, types, HEAD and hashed assets with appropriate caching', async t => {
  const { port } = await fixture(t);
  for (const route of ['/', '/items/42', '/admin/verifications/2', '/claims/mine?itemId=5']) {
    const result = await send(port, route);
    assert.equal(result.status, 200); assert.match(result.body.toString(), /合成演示/);
    assert.equal(result.headers['cache-control'], 'no-cache');
    assert.equal(result.headers['content-type'], 'text/html; charset=utf-8');
  }
  const js = await send(port, '/assets/index-Abc123xy.js');
  assert.equal(js.status, 200); assert.equal(js.headers['content-type'], 'text/javascript; charset=utf-8');
  assert.equal(js.headers['cache-control'], 'public, max-age=31536000, immutable');
  assert.equal(js.headers['x-content-type-options'], 'nosniff');
  assert.equal((await send(port, '/assets/style-Def456xy.css')).headers['content-type'], 'text/css; charset=utf-8');
  assert.equal((await send(port, '/icon.svg')).headers['content-type'], 'image/svg+xml');
  const head = await send(port, '/items', { method: 'HEAD' });
  assert.equal(head.status, 200); assert.equal(head.body.length, 0); assert.ok(Number(head.headers['content-length']) > 0);
});

test('missing assets do not receive SPA HTML and static requests cannot write', async t => {
  const { port } = await fixture(t);
  for (const route of ['/assets/missing.js', '/missing.css', '/assets/no-extension', '/assets']) {
    const result = await send(port, route);
    assert.equal(result.status, 404, route); assert.doesNotMatch(result.body.toString(), /合成演示/);
  }
  const result = await send(port, '/items', { method: 'POST', body: 'not written' });
  assert.equal(result.status, 405); assert.equal(result.headers.allow, 'GET, HEAD');
});

test('rejects raw, encoded and double encoded traversal, hidden files and absolute proxy targets', async t => {
  const { port } = await fixture(t);
  for (const route of ['/../outside.txt', '/%2e%2e/outside.txt', '/%252e%252e/outside.txt', '/assets/../index.html',
    '/assets/%2e%2e/index.html', '/assets%2f..%2foutside.txt', '/assets%5c..%5coutside.txt',
    '/assets\\..\\outside.txt', '/.env', '/%2eenv', '/%00', '/%not-valid', '//example.test/api',
    'http://example.test/api', '/C:/outside.txt', '/api/../outside.txt']) {
    assert.equal((await send(port, route)).status, 400, route);
  }
});

test('rejects symbolic-link directory escape without exposing outside content', async t => {
  const { port, directory, temporary } = await fixture(t);
  const outside = path.join(temporary, 'outside');
  await mkdir(outside); await writeFile(path.join(outside, 'secret.txt'), 'not-to-be-disclosed');
  await symlink(outside, path.join(directory, 'escape'), process.platform === 'win32' ? 'junction' : 'dir');
  const result = await send(port, '/escape/secret.txt');
  assert.equal(result.status, 403); assert.doesNotMatch(result.body.toString(), /not-to-be-disclosed/);
  assert.equal((await send(port, '/escape/missing-route')).status, 403);
});

test('API forwards method, query, token, multipart bytes and upstream status without caching', async t => {
  let received;
  const { port } = await fixture(t, (request, response) => {
    const chunks = [];
    request.on('data', chunk => chunks.push(chunk));
    request.on('end', () => {
      received = { method: request.method, target: request.url, token: request.headers['x-token'],
        contentType: request.headers['content-type'], forwarded: request.headers['x-forwarded-for'], body: Buffer.concat(chunks) };
      response.writeHead(409, { 'Content-Type': 'application/json', 'Cache-Control': 'public, max-age=9999' });
      response.end('{"code":409,"message":"synthetic conflict","data":null}');
    });
  });
  const body = Buffer.from('--synthetic\r\nContent-Disposition: form-data; name="file"; filename="item.png"\r\nContent-Type: image/png\r\n\r\n\x00\x01\x02\r\n--synthetic--\r\n', 'latin1');
  const result = await send(port, '/api/uploads/images?source=demo', { method: 'POST', body,
    headers: { 'Content-Type': 'multipart/form-data; boundary=synthetic', 'Content-Length': body.length,
      'X-Token': 'synthetic-test-token', 'X-Forwarded-For': '198.51.100.15' } });
  assert.equal(result.status, 409); assert.equal(result.headers['cache-control'], 'no-store');
  assert.deepEqual(received, { method: 'POST', target: '/api/uploads/images?source=demo', token: 'synthetic-test-token',
    contentType: 'multipart/form-data; boundary=synthetic', forwarded: undefined, body });
  assert.equal(JSON.parse(result.body).code, 409);
  assert.equal((await send(port, '/api')).status, 409);
  assert.equal(received.target, '/api');
  assert.equal((await send(port, '/api/')).status, 409);
  assert.equal(received.target, '/api/');
});

test('API preserves error body/status and does not follow upstream redirects', async t => {
  let calls = 0;
  let redirectedCalls = 0;
  const trap = http.createServer((_request, response) => { redirectedCalls++; response.end('must not be fetched'); });
  const trapPort = await listen(trap);
  t.after(() => close(trap));
  const target = `http://127.0.0.1:${trapPort}/not-the-backend`;
  const { port } = await fixture(t, (request, response) => {
    calls++;
    if (request.url === '/api/redirect') { response.writeHead(302, { Location: target }); response.end(); }
    else { response.writeHead(401, { 'Content-Type': 'application/json' }); response.end('{"code":401,"message":"synthetic","data":null}'); }
  });
  const unauthorized = await send(port, '/api/protected');
  assert.equal(unauthorized.status, 401); assert.equal(JSON.parse(unauthorized.body).code, 401);
  const redirect = await send(port, '/api/redirect');
  assert.equal(redirect.status, 302); assert.equal(redirect.headers.location, target);
  assert.equal(calls, 2); assert.equal(redirectedCalls, 0);
});

test('unavailable backend returns a fixed 503 JSON envelope without internals', async t => {
  const { port, backend } = await fixture(t);
  await close(backend);
  const result = await send(port, '/api/items');
  assert.equal(result.status, 503); assert.equal(result.headers['cache-control'], 'no-store');
  assert.deepEqual(JSON.parse(result.body), { code: 503, message: '本地演示后端暂不可用，请确认后端已启动。', data: null });
  assert.doesNotMatch(result.body.toString(), /ECONNREFUSED|stack|token/i);
});

test('rejects oversized declared and chunked bodies while permitting exactly 6 MiB', async t => {
  let accepted = 0;
  const { port } = await fixture(t, (request, response) => {
    let count = 0;
    request.on('data', chunk => { count += chunk.length; });
    request.on('end', () => { accepted++; response.end(String(count)); });
    request.on('error', () => {});
  });
  const declared = await send(port, '/api/upload', { method: 'POST', headers: { 'Content-Length': MAX_BODY_BYTES + 1 } });
  assert.equal(declared.status, 413); assert.equal(accepted, 0);
  const exact = await send(port, '/api/upload', { method: 'POST', body: Buffer.alloc(MAX_BODY_BYTES, 1) });
  assert.equal(exact.status, 200); assert.equal(Number(exact.body.toString()), MAX_BODY_BYTES); assert.equal(accepted, 1);
  const chunked = await send(port, '/api/upload', { method: 'POST', chunks: [Buffer.alloc(MAX_BODY_BYTES, 2), Buffer.from('x')] });
  assert.equal(chunked.status, 413); assert.equal(accepted, 1);
});

test('test seam also refuses external backends and missing builds', async t => {
  const { directory, temporary } = await fixture(t);
  for (const backendOrigin of ['http://example.test:18080', 'http://localhost:18080', 'https://127.0.0.1:18080',
    'http://127.0.0.1:18080/other', 'http://user:pass@127.0.0.1:18080', 'http://127.0.0.1:18080?url=other']) {
    await assert.rejects(createDemoServer({ directory, backendOrigin }));
  }
  await assert.rejects(createDemoServer({ directory: temporary }));
  await assert.rejects(createDemoServer({ directory: 'frontend/dist' }));
});
