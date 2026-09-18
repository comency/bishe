#!/usr/bin/env node
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const MAX_BODY_BYTES = 4096;
const REQUEST_TIMEOUT_MS = 5000;

function check(condition, message) {
  if (!condition) throw new Error(message);
}

export function validateBaseUrl(configured) {
  let url;
  try { url = new URL(configured); } catch { throw new Error('Base URL must be an absolute URL.'); }
  check(!url.username && !url.password, 'Base URL must not contain credentials.');
  check(!url.pathname || url.pathname === '/', 'Base URL must not contain a path.');
  check(!url.search && !url.hash, 'Base URL must not contain query or fragment data.');
  const loopback = url.hostname === '127.0.0.1' || url.hostname === '[::1]' || url.hostname === 'localhost';
  check(url.protocol === 'https:' || (url.protocol === 'http:' && loopback),
      'Deployment checks require HTTPS, except explicit loopback HTTP.');
  return new URL(url.origin);
}

async function boundedJson(response) {
  const declared = Number(response.headers.get('content-length'));
  check(!Number.isFinite(declared) || declared <= MAX_BODY_BYTES, 'Response body exceeds the deployment-check limit.');
  const reader = response.body?.getReader();
  check(reader, 'Response body is missing.');
  const chunks = [];
  let size = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.byteLength;
    if (size > MAX_BODY_BYTES) {
      await reader.cancel();
      throw new Error('Response body exceeds the deployment-check limit.');
    }
    chunks.push(value);
  }
  try {
    const body = Buffer.concat(chunks.map(value => Buffer.from(value))).toString('utf8');
    return JSON.parse(body);
  } catch {
    throw new Error('Response body must be bounded JSON.');
  }
}

async function get(base, path, expectedStatus) {
  const url = new URL(path, base);
  check(url.origin === base.origin && url.pathname === path, 'Probe path must remain on the selected origin.');
  let response;
  try {
    response = await fetch(url, {
      method: 'GET',
      headers: { Accept: 'application/json' },
      redirect: 'error',
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
  } catch {
    throw new Error(`GET ${path} must complete without timeout or redirect.`);
  }
  check(response.status === expectedStatus, `GET ${path} must return HTTP ${expectedStatus}.`);
  check(response.headers.get('content-type')?.toLowerCase().includes('application/json'),
      `GET ${path} must return JSON.`);
  check(response.headers.get('cache-control')?.toLowerCase().includes('no-store'),
      `GET ${path} must disable caching.`);
  return boundedJson(response);
}

export async function checkDeployment(configured) {
  const base = validateBaseUrl(configured);
  const live = await get(base, '/api/health/live', 200);
  check(live?.status === 'UP' && Object.keys(live).length === 1, 'Liveness response must be the fixed UP shape.');

  const ready = await get(base, '/api/health/ready', 200);
  check(ready?.status === 'UP' && Object.keys(ready).length === 1, 'Readiness response must be the fixed UP shape.');

  const config = await get(base, '/api/public/config', 200);
  check(config?.code === 0 && config?.errorCode == null && config?.traceId == null &&
      config?.data && config.data.aiEnabled === false, 'Public configuration must be successful with AI disabled.');

  const unauthorized = await get(base, '/api/users/me', 401);
  check(unauthorized?.code === -1 && unauthorized?.data === null &&
      unauthorized?.errorCode === 'AUTH_REQUIRED' && typeof unauthorized?.traceId === 'string' &&
      unauthorized.traceId.length > 0, 'Anonymous protected API must return the fixed authentication failure shape.');
  return { checks: 4, origin: base.origin };
}

const invokedDirectly = process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url;
if (invokedDirectly) {
  if (process.argv.includes('--help')) {
    console.log('Usage: node scripts/check-deployment.mjs --confirm-read-only --base-url <https-url-or-loopback-http>');
    console.log('Performs four bounded GET checks. Sends no credentials and makes no writes.');
  } else {
    const index = process.argv.indexOf('--base-url');
    if (!process.argv.includes('--confirm-read-only') || index < 0 || index + 1 >= process.argv.length) {
      console.error('Explicit --confirm-read-only and --base-url are required. Use --help.');
      process.exitCode = 1;
    } else {
      try {
        const result = await checkDeployment(process.argv[index + 1]);
        console.log(`PASS: ${result.checks} read-only deployment endpoints at ${result.origin}.`);
      } catch (error) {
        console.error(error instanceof Error ? error.message : 'Deployment check failed.');
        process.exitCode = 1;
      }
    }
  }
}
