import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { checkDeployment, validateBaseUrl } from '../check-deployment.mjs';

function response(path) {
  if (path === '/api/health/live' || path === '/api/health/ready') return [200, { status: 'UP' }];
  if (path === '/api/public/config') return [200, { code: 0, message: 'success', data: { aiEnabled: false } }];
  if (path === '/api/users/me') return [401, { code: -1, message: 'login required', data: null,
    errorCode: 'AUTH_REQUIRED', traceId: 'synthetic-trace' }];
  return [404, {}];
}

async function fixture(handler = (request, reply) => {
  const [status, body] = response(request.url);
  reply.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  reply.end(JSON.stringify(body));
}) {
  const server = createServer(handler);
  await new Promise((accept, reject) => server.listen(0, '127.0.0.1', error => error ? reject(error) : accept()));
  const { port } = server.address();
  return { url: `http://127.0.0.1:${port}`, close: () => new Promise(accept => server.close(accept)) };
}

test('valid deployment contract performs four read-only GET requests', async () => {
  const methods = [];
  const target = await fixture((request, reply) => {
    methods.push(request.method);
    const [status, body] = response(request.url);
    reply.writeHead(status, { 'Content-Type': 'application/json;charset=UTF-8', 'Cache-Control': 'no-store' });
    reply.end(JSON.stringify(body));
  });
  try {
    assert.deepEqual(await checkDeployment(target.url), { checks: 4, origin: target.url });
    assert.deepEqual(methods, ['GET', 'GET', 'GET', 'GET']);
  } finally { await target.close(); }
});

test('only HTTPS or explicit loopback HTTP origins are accepted', () => {
  assert.equal(validateBaseUrl('https://campus.example').origin, 'https://campus.example');
  assert.equal(validateBaseUrl('http://localhost:8080').origin, 'http://localhost:8080');
  for (const value of ['http://campus.example', 'ftp://127.0.0.1', 'https://user:secret@campus.example',
    'https://campus.example/path', 'https://campus.example?private=value']) {
    assert.throws(() => validateBaseUrl(value));
  }
});

test('not-ready status fails without echoing the response body', async () => {
  const target = await fixture((request, reply) => {
    const body = request.url === '/api/health/ready' ? { status: 'DOWN', private: 'synthetic-secret' } : response(request.url)[1];
    const status = request.url === '/api/health/ready' ? 503 : response(request.url)[0];
    reply.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    reply.end(JSON.stringify(body));
  });
  try {
    await assert.rejects(checkDeployment(target.url), error =>
      error.message === 'GET /api/health/ready must return HTTP 200.' && !error.message.includes('synthetic-secret'));
  } finally { await target.close(); }
});

test('redirects are refused', async () => {
  const target = await fixture((request, reply) => {
    reply.writeHead(302, { Location: '/api/health/live' }); reply.end();
  });
  try {
    await assert.rejects(checkDeployment(target.url), /without timeout or redirect/);
  } finally { await target.close(); }
});

test('oversized response is refused before parsing', async () => {
  const target = await fixture((request, reply) => {
    const body = JSON.stringify({ status: 'UP', padding: 'x'.repeat(5000) });
    reply.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store',
      'Content-Length': Buffer.byteLength(body) });
    reply.end(body);
  });
  try {
    await assert.rejects(checkDeployment(target.url), /exceeds the deployment-check limit/);
  } finally { await target.close(); }
});
