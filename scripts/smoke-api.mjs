#!/usr/bin/env node
import { randomBytes } from 'node:crypto';

// Legacy-contract regression only: this does not test campus verification or claims.
// Never accept a configurable host, and never follow redirects with test credentials.
const BASE_URL = 'http://127.0.0.1:18080';
const REQUEST_TIMEOUT_MS = 15_000;
const HELP = `Usage: node scripts/smoke-api.mjs --confirm-test-environment

Requires Node.js 22+ and TEST_ADMIN_PASSWORD in the process environment.
The existing integration administrator username is admin; no password is supplied
by this script. The only permitted backend is http://127.0.0.1:18080.

This opt-in test creates one synthetic user and one item in the integration
database. It exercises the CURRENT legacy API, not the planned campus-identity
or claim workflow. It does not delete records, flush Redis, or change containers.
It attempts to log out both sessions in finally, even after a failed assertion.
Passwords, tokens, request bodies, and response bodies are never printed or saved.
Record identifiers are printed for a later, separately authorized cleanup.
Do not run against a shared, development, or production database.

Options:
  --confirm-test-environment  Confirm the fixed backend uses isolated test data.
  --help                     Show this help without any network requests.
`;

class SmokeFailure extends Error {}

let assertionCount = 0;
let currentStep = 'preflight';

function check(condition, label) {
  if (!condition) throw new SmokeFailure(label);
  assertionCount += 1;
}

function step(label) {
  currentStep = label;
  console.log(`STEP ${label}`);
}

function isObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function isId(value) {
  return Number.isSafeInteger(value) && value > 0;
}

async function request(method, path, { token, body, status = 200 } = {}) {
  const url = new URL(path, BASE_URL);
  check(url.origin === BASE_URL && url.pathname.startsWith('/api/'), 'fixed test origin');
  const headers = { Accept: 'application/json' };
  if (token) headers['X-Token'] = token;
  if (body !== undefined) headers['Content-Type'] = 'application/json';

  let response;
  let result;
  try {
    response = await fetch(url, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      redirect: 'error',
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
    check(response.status === status, `expected HTTP ${status}`);
    check(response.headers.get('content-type')?.includes('application/json'), 'JSON content type');
    result = await response.json();
  } catch (error) {
    if (error instanceof SmokeFailure) throw error;
    // Network and parser exceptions can contain server-controlled text: do not log them.
    throw new SmokeFailure('request completed within timeout with valid JSON and no redirect');
  }

  check(isObject(result), 'response envelope is an object');
  check(result.code === (status === 200 ? 0 : -1), 'HTTP and business code agree');
  check(typeof result.message === 'string', 'response message exists');
  check(Object.hasOwn(result, 'data'), 'response data exists');
  if (status !== 200) check(result.data === null, 'error response has null data');
  return result.data;
}

function checkPrivateFieldsAbsent(value) {
  if (value === null || typeof value !== 'object') return;
  for (const [key, child] of Object.entries(value)) {
    check(!/^(publisher|password|passwordHash|token)$/i.test(key), 'item response excludes private fields');
    checkPrivateFieldsAbsent(child);
  }
}

function findItem(records, itemId) {
  check(Array.isArray(records), 'legacy listing remains an array');
  return records.find((record) => isObject(record) && record.id === itemId);
}

function checkItem(item, { itemId, userId, state, occurredAt }) {
  check(isObject(item), 'expected item exists');
  check(item.id === itemId, 'item identifier matches');
  check(item.publisherId === userId, 'publisherId survives serialization');
  check(item.status === state, `item state is ${state}`);
  check(item.occurredAt === occurredAt, 'occurredAt remains the submitted date');
  check(typeof item.createdAt === 'string' && /^\d{4}-\d{2}-\d{2}T/.test(item.createdAt),
    'createdAt is a legacy ISO date-time string');
  checkPrivateFieldsAbsent(item);
}

async function runSmoke() {
  const adminPassword = process.env.TEST_ADMIN_PASSWORD;
  check(typeof adminPassword === 'string' && adminPassword.trim().length > 0,
    'TEST_ADMIN_PASSWORD must be set explicitly');
  check(Number(process.versions.node.split('.')[0]) >= 22, 'Node.js 22 or newer');

  const suffix = randomBytes(8).toString('hex');
  const username = `smoke_${suffix}`;
  const password = randomBytes(18).toString('hex');
  const title = `SMOKE_${suffix}`;
  const occurredAt = new Date().toISOString().slice(0, 10);
  const payload = {
    title,
    description: 'Synthetic integration smoke-test item. No real personal data.',
    type: 'FOUND',
    category: 'TEST_ONLY',
    location: 'TEST_ONLY',
    occurredAt,
  };
  const sessions = new Map();
  let registrationAttempted = false;
  let userId;
  let itemId;
  let mainFailure;
  let cleanupFailed = false;

  try {
    step('legacy contract: no token is rejected');
    await request('GET', '/api/items', { status: 401 });

    step('register synthetic user');
    registrationAttempted = true;
    const registration = await request('POST', '/api/auth/register', {
      body: { username, password, nickname: 'Smoke Test User' },
    });
    check(registration === null, 'legacy registration returns null data');

    step('log in synthetic user');
    const login = await request('POST', '/api/auth/login', { body: { username, password } });
    if (isObject(login) && typeof login.token === 'string' && login.token.length > 0) {
      sessions.set('user', login.token);
    }
    check(sessions.has('user'), 'user login returns a token');
    check(isId(login.userId), 'user login returns a safe positive identifier');
    userId = login.userId;
    check(login.username === username && login.role === 'USER', 'ordinary account identity and role');
    const userToken = sessions.get('user');

    step('ordinary user cannot access administrator listing');
    await request('GET', '/api/admin/items/pending', { token: userToken, status: 403 });

    step('publish pending item');
    const created = await request('POST', '/api/items', { token: userToken, body: payload });
    check(isObject(created) && isId(created.id), 'created item has an identifier');
    itemId = created.id;
    checkItem(created, { itemId, userId, state: 'PENDING', occurredAt });

    step('owner can see pending item');
    const mine = await request('GET', '/api/items/mine', { token: userToken });
    checkItem(findItem(mine, itemId), { itemId, userId, state: 'PENDING', occurredAt });

    const searchPath = `/api/items?${new URLSearchParams({ keyword: title, type: 'FOUND' })}`;
    step('pending item absent from public-business search');
    const pendingSearch = await request('GET', searchPath, { token: userToken });
    check(findItem(pendingSearch, itemId) === undefined, 'pending item is not publicly listed');

    step('log in existing test administrator');
    const adminLogin = await request('POST', '/api/auth/login', {
      body: { username: 'admin', password: adminPassword },
    });
    if (isObject(adminLogin) && typeof adminLogin.token === 'string' && adminLogin.token.length > 0) {
      sessions.set('admin', adminLogin.token);
    }
    check(sessions.has('admin'), 'administrator login returns a token');
    check(adminLogin.username === 'admin' && adminLogin.role === 'ADMIN', 'administrator role');

    step('administrator approves only the newly created item');
    const approved = await request('PUT', `/api/admin/items/${itemId}/review`, {
      token: sessions.get('admin'), body: { status: 'APPROVED' },
    });
    checkItem(approved, { itemId, userId, state: 'APPROVED', occurredAt });

    step('first approved search after review invalidates pending cache');
    const first = findItem(await request('GET', searchPath, { token: userToken }), itemId);
    checkItem(first, { itemId, userId, state: 'APPROVED', occurredAt });

    step('repeat search preserves publisher and dates on the cache path');
    const second = findItem(await request('GET', searchPath, { token: userToken }), itemId);
    checkItem(second, { itemId, userId, state: 'APPROVED', occurredAt });
    check(first.publisherId === second.publisherId, 'publisherId is stable across repeat search');
    check(first.createdAt === second.createdAt, 'createdAt is stable across repeat search');
    check(first.occurredAt === second.occurredAt, 'occurredAt is stable across repeat search');

    step('ordinary edit returns item to pending');
    const edited = await request('PUT', `/api/items/${itemId}`, {
      token: userToken,
      body: { ...payload, description: `${payload.description} Edited by its owner.` },
    });
    checkItem(edited, { itemId, userId, state: 'PENDING', occurredAt });

    step('edit invalidates the approved search cache');
    const afterEdit = await request('GET', searchPath, { token: userToken });
    check(findItem(afterEdit, itemId) === undefined, 'edited pending item disappears from public search');

    step('logout invalidates the old user token');
    check(await request('POST', '/api/auth/logout', { token: userToken }) === null,
      'logout returns null data');
    await request('GET', '/api/items/mine', { token: userToken, status: 401 });
  } catch (error) {
    mainFailure = {
      step: currentStep,
      label: error instanceof SmokeFailure ? error.message : 'unexpected local test failure',
    };
  } finally {
    // Repeating logout is intentional: legacy logout is idempotent. No other session is touched.
    for (const [kind, token] of sessions) {
      step(`finally: logout own ${kind} session`);
      try {
        check(await request('POST', '/api/auth/logout', { token }) === null, 'cleanup logout succeeds');
      } catch {
        cleanupFailed = true;
        console.error(`FAIL finally: logout own ${kind} session`);
      }
    }
    sessions.clear();
    // Also print an attempted username on network failure: registration may have committed.
    if (registrationAttempted) console.log(`RECORD_CANDIDATE username=${username}`);
    if (isId(userId)) console.log(`RECORD userId=${userId}`);
    if (isId(itemId)) console.log(`RECORD itemId=${itemId}`);
  }

  if (mainFailure) {
    console.error(`FAIL ${mainFailure.step}: ${mainFailure.label}`);
  }
  console.log(`${mainFailure || cleanupFailed ? 'FAIL' : 'PASS'} assertions=${assertionCount}`);
  if (mainFailure || cleanupFailed) process.exitCode = 1;
}

const args = process.argv.slice(2);
if (args.length === 1 && args[0] === '--help') {
  console.log(HELP);
} else if (args.length !== 1 || args[0] !== '--confirm-test-environment') {
  console.error('FAIL preflight: use --help or the explicit --confirm-test-environment flag');
  process.exitCode = 2;
} else {
  try {
    await runSmoke();
  } catch (error) {
    console.error(`FAIL preflight: ${error instanceof SmokeFailure ? error.message : 'local setup failed'}`);
    process.exitCode = 1;
  }
}
