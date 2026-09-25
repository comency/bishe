import http from 'node:http';
import path from 'node:path';
import { open, realpath, stat } from 'node:fs/promises';

export const DEMO_HOST = '127.0.0.1';
export const DEMO_PORT = 15174;
export const DEMO_BACKEND = 'http://127.0.0.1:18080';
export const MAX_BODY_BYTES = 6 * 1024 * 1024;

const MIME = new Map(Object.entries({
  '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8', '.map': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg',
  '.webp': 'image/webp', '.gif': 'image/gif', '.ico': 'image/x-icon',
  '.woff': 'font/woff', '.woff2': 'font/woff2', '.ttf': 'font/ttf', '.txt': 'text/plain; charset=utf-8',
}));
const HOP_HEADERS = new Set(['connection', 'keep-alive', 'proxy-authenticate', 'proxy-authorization',
  'te', 'trailer', 'transfer-encoding', 'upgrade']);

function headersWithoutHopByHop(headers) {
  const excluded = new Set([...HOP_HEADERS, ...String(headers.connection ?? '').toLowerCase().split(',').map(x => x.trim())]);
  return Object.fromEntries(Object.entries(headers).filter(([name]) => !excluded.has(name.toLowerCase())));
}

function jsonError(response, status, message, close = false) {
  if (response.destroyed || response.writableEnded) return;
  if (response.headersSent) { response.destroy(); return; }
  const body = JSON.stringify({ code: status, message, data: null });
  response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body), 'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff', ...(close ? { Connection: 'close' } : {}) });
  response.end(body);
}

// Inspect the unnormalised request target: URL() would erase /../ before validation.
function requestPath(target) {
  if (typeof target !== 'string' || !target.startsWith('/') || target.startsWith('//') || /[\\\u0000-\u0020\u007f#]/.test(target)) return null;
  const rawPath = target.split('?', 1)[0];
  // Encoded separators and repeated encoding are unnecessary for our generated asset paths.
  if (/%(?:2f|5c)/i.test(rawPath)) return null;
  let decoded;
  try { decoded = decodeURIComponent(rawPath); } catch { return null; }
  if (/[\\%:\u0000-\u001f\u007f]/.test(decoded)) return null;
  const segments = decoded.split('/');
  if (segments.some(segment => segment.startsWith('.'))) return null;
  return decoded;
}

function contained(root, file) {
  const relative = path.relative(root, file);
  return relative !== '' && relative !== '..' && !relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative);
}

async function safeFile(root, candidate) {
  if (!contained(root, candidate)) return { forbidden: true };
  try {
    const resolved = await realpath(candidate);
    if (!contained(root, resolved)) return { forbidden: true };
    const file = await open(resolved, 'r');
    const metadata = await file.stat();
    if (!metadata.isFile()) { await file.close(); return {}; }
    return { file, metadata, resolved };
  } catch (error) {
    if (['ENOENT', 'ENOTDIR', 'EISDIR'].includes(error.code)) {
      // A missing leaf below an escaping junction must not become a SPA fallback.
      let ancestor = path.dirname(candidate);
      while (contained(root, ancestor)) {
        try { return contained(root, await realpath(ancestor)) ? {} : { forbidden: true }; }
        catch (ancestorError) {
          if (!['ENOENT', 'ENOTDIR'].includes(ancestorError.code)) return { forbidden: true };
          ancestor = path.dirname(ancestor);
        }
      }
      return {};
    }
    if (['EACCES', 'EPERM', 'ELOOP'].includes(error.code)) return { forbidden: true };
    throw error;
  }
}

function proxyApi(request, response, backend) {
  const length = request.headers['content-length'];
  if (length !== undefined && (!Number.isSafeInteger(Number(length)) || Number(length) > MAX_BODY_BYTES)) {
    request.resume(); jsonError(response, 413, '请求内容超过 6 MiB 限制。', true); return;
  }
  const headers = headersWithoutHopByHop(request.headers);
  headers.host = backend.host;
  // The demo listens only on loopback and does not claim a forwarded remote identity.
  for (const name of Object.keys(headers)) if (name === 'forwarded' || name.startsWith('x-forwarded-')) delete headers[name];
  let failed = false;
  let bytes = 0;
  const upstream = http.request({ hostname: DEMO_HOST, port: backend.port, method: request.method,
    path: request.url, headers, agent: false }, reply => {
    if (failed || response.destroyed) { reply.destroy(); return; }
    const responseHeaders = headersWithoutHopByHop(reply.headers);
    responseHeaders['cache-control'] = 'no-store';
    responseHeaders.pragma = 'no-cache';
    responseHeaders['x-content-type-options'] = 'nosniff';
    response.writeHead(reply.statusCode ?? 502, responseHeaders);
    reply.on('error', () => response.destroy());
    // http.request returns redirect responses directly; it never follows Location.
    reply.pipe(response);
  });
  const unavailable = () => {
    if (failed) return;
    failed = true;
    upstream.destroy();
    request.resume();
    jsonError(response, 503, '本地演示后端暂不可用，请确认后端已启动。', true);
  };
  upstream.on('error', unavailable);
  upstream.setTimeout(30000, unavailable);
  upstream.on('drain', () => { if (!failed) request.resume(); });
  request.on('data', chunk => {
    if (failed) return;
    bytes += chunk.length;
    if (bytes > MAX_BODY_BYTES) {
      failed = true; upstream.destroy(); request.resume();
      jsonError(response, 413, '请求内容超过 6 MiB 限制。', true);
    } else if (!upstream.write(chunk)) request.pause();
  });
  request.on('end', () => { if (!failed) upstream.end(); });
  request.on('aborted', () => { failed = true; upstream.destroy(); });
  request.on('error', () => { failed = true; upstream.destroy(); });
  response.on('close', () => { if (!response.writableFinished) { failed = true; upstream.destroy(); } });
}

export function parseDemoArguments(args) {
  if (args.length === 1 && args[0] === '--help') return { help: true };
  let confirmed = false;
  let directory;
  for (let index = 0; index < args.length; index++) {
    if (args[index] === '--confirm-local-demo' && !confirmed) confirmed = true;
    else if (args[index] === '--directory' && directory === undefined && args[index + 1] && !args[index + 1].startsWith('--')) directory = args[++index];
    else throw new Error('参数不合法；请使用 --help 查看固定本地演示配置。');
  }
  if (!confirmed || !directory || !path.isAbsolute(directory)) throw new Error('必须提供 --confirm-local-demo 与 --directory 的绝对路径。');
  return { directory };
}

/** Unbound server for HTTP tests. The CLI always binds fixed loopback ports. */
export async function createDemoServer({ directory, backendOrigin = DEMO_BACKEND }) {
  if (!directory || !path.isAbsolute(directory)) throw new Error('演示静态目录必须为绝对路径。');
  const backend = new URL(backendOrigin);
  if (backend.protocol !== 'http:' || backend.hostname !== DEMO_HOST || !backend.port ||
      backend.username || backend.password || backend.pathname !== '/' || backend.search || backend.hash) throw new Error('后端必须为受控的本机 HTTP 地址。');
  const root = await realpath(directory);
  if (!(await stat(root)).isDirectory()) throw new Error('演示静态目录不存在。');
  const index = await safeFile(root, path.join(root, 'index.html'));
  if (!index.file) throw new Error('演示静态目录必须包含本目录内的 index.html。');
  await index.file.close();

  const server = http.createServer(async (request, response) => {
    try {
      const pathname = requestPath(request.url);
      if (pathname === null) { request.resume(); jsonError(response, 400, '请求路径不合法。', true); return; }
      if (pathname === '/api' || pathname.startsWith('/api/')) { proxyApi(request, response, backend); return; }
      if (!['GET', 'HEAD'].includes(request.method)) {
        request.resume(); response.setHeader('Allow', 'GET, HEAD'); jsonError(response, 405, '静态资源仅支持读取。'); return;
      }
      let selected = await safeFile(root, path.join(root, pathname === '/' ? 'index.html' : pathname));
      if (selected.forbidden) { jsonError(response, 403, '不可访问此资源。'); return; }
      if (!selected.file && !path.posix.extname(pathname) && pathname !== '/assets' && !pathname.startsWith('/assets/')) {
        selected = await safeFile(root, path.join(root, 'index.html'));
      }
      if (selected.forbidden) { jsonError(response, 403, '不可访问此资源。'); return; }
      if (!selected.file) { jsonError(response, 404, '资源不存在。'); return; }
      const hashed = pathname.startsWith('/assets/') && /[-.][A-Za-z0-9_-]{8,}\.[a-z0-9]+$/i.test(path.basename(selected.resolved));
      response.writeHead(200, {
        'Content-Type': MIME.get(path.extname(selected.resolved).toLowerCase()) ?? 'application/octet-stream',
        'Content-Length': selected.metadata.size,
        'Cache-Control': hashed ? 'public, max-age=31536000, immutable' : 'no-cache',
        'X-Content-Type-Options': 'nosniff',
      });
      if (request.method === 'HEAD') { await selected.file.close(); response.end(); return; }
      const stream = selected.file.createReadStream({ autoClose: true });
      stream.on('error', () => response.destroy());
      response.on('close', () => stream.destroy());
      stream.pipe(response);
    } catch {
      jsonError(response, 500, '本地演示资源读取失败。');
    }
  });
  server.headersTimeout = 10000;
  server.requestTimeout = 35000;
  server.keepAliveTimeout = 5000;
  server.on('upgrade', (_request, socket) => socket.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n'));
  server.on('connect', (_request, socket) => socket.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n'));
  return server;
}
