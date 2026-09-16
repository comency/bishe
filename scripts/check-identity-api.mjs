#!/usr/bin/env node
import { randomBytes } from 'node:crypto';

// Actual HTTP integration tests. No mocks, direct database writes or Redis flushes.
// Fixed loopback destination and redirects disabled keep credentials in test scope.
const BASE_URL = 'http://127.0.0.1:18080';
const HELP = `Usage: node scripts/check-identity-api.mjs --confirm-test-environment

Requires Node.js 22+ and TEST_ADMIN_PASSWORD in the process environment.
The only backend is http://127.0.0.1:18080 (isolated integration profile).
The public configuration must say isTest=true before any account is created.

Tests API-01 through API-13: accounts, profiles, manual identity review, rejection,
resubmission, revocation, reopening, version conflicts, concurrent submissions
and reviews, privacy, role checks, existing-token admission and logout.
Creates one synthetic ordinary user, three identity applications and one item.
Uses the existing admin only to review this new synthetic account. Self-review
denial checks do not submit or modify the existing administrator's identity.
No records are deleted, no database statements are run, and Redis is not flushed.
Only sessions created by this run are logged out in finally. Record identifiers
are printed to support a later, separately authorized cleanup. Passwords, tokens,
request bodies, response bodies and internal evidence are never printed or saved.
Do not run against a shared, development or production database.

Options:
  --confirm-test-environment  Confirm the fixed backend uses isolated test data.
  --help                     Show help without making any network requests.
`;

class CheckFailure extends Error {}
let assertions = 0;
let currentStep = 'preflight';

function check(condition, label) {
  if (!condition) throw new CheckFailure(label);
  assertions += 1;
}

function step(label) {
  currentStep = label;
  console.log(`STEP ${label}`);
}

const object = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
const id = (value) => Number.isSafeInteger(value) && value > 0;
const version = (value) => Number.isSafeInteger(value) && value >= 0;

async function request(method, path, { token, body, status = 200, errorCode } = {}) {
  const url = new URL(path, BASE_URL);
  check(url.origin === BASE_URL && url.pathname.startsWith('/api/'), 'fixed test origin');
  const headers = { Accept: 'application/json' };
  if (token) headers['X-Token'] = token;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  let response;
  let envelope;
  try {
    response = await fetch(url, {
      method, headers, body: body === undefined ? undefined : JSON.stringify(body),
      redirect: 'error', signal: AbortSignal.timeout(15_000),
    });
    const allowed = Array.isArray(status) ? status : [status];
    check(allowed.includes(response.status), `expected HTTP ${allowed.join(' or ')}; got ${response.status}`);
    check(response.headers.get('content-type')?.includes('application/json'), 'JSON content type');
    envelope = await response.json();
  } catch (error) {
    if (error instanceof CheckFailure) throw error;
    throw new CheckFailure('request completes within timeout with valid JSON and no redirect');
  }
  check(object(envelope), 'response envelope');
  check(envelope.code === (response.status === 200 ? 0 : -1), 'HTTP and business code agree');
  check(typeof envelope.message === 'string' && Object.hasOwn(envelope, 'data'), 'complete response envelope');
  if (response.status !== 200) {
    check(envelope.data === null, 'errors have null data');
    check(typeof envelope.traceId === 'string' && envelope.traceId.length > 0, 'error trace identifier');
    check(typeof envelope.errorCode === 'string', 'machine-readable error code');
    if (errorCode) {
      const allowed = Array.isArray(errorCode) ? errorCode : [errorCode];
      check(allowed.includes(envelope.errorCode), `error code is ${allowed.join(' or ')}`);
    }
  }
  return { status: response.status, data: envelope.data, errorCode: envelope.errorCode, message: envelope.message };
}

async function data(method, path, options) {
  return (await request(method, path, options)).data;
}

function privateFieldsAbsent(value, { internal = true } = {}) {
  if (value === null || typeof value !== 'object') return;
  for (const [key, child] of Object.entries(value)) {
    check(!/^(password|passwordHash|password_hash|token)$/i.test(key), 'DTO excludes credentials');
    if (internal) {
      check(!/^(method|evidenceSummary|internalNote|reviewerId)$/i.test(key), 'ordinary DTO excludes internal review fields');
    }
    privateFieldsAbsent(child, { internal });
  }
}

function summary(value, { userId, state, expectedVersion }) {
  check(object(value) && value.userId === userId, 'summary belongs to the expected account');
  check(value.status === state, `verification state is ${state}`);
  check(value.version === expectedVersion, 'summary version advances exactly once');
  check(value.isTest === true, 'synthetic verification remains explicitly test-only');
  check(Object.hasOwn(value, 'expiresAt') && Object.hasOwn(value, 'validThrough'), 'expiry fields exist');
}

function detail(value, { userId, state, expectedVersion, applications, applicationVersion, admin = false }) {
  check(object(value), 'verification detail exists');
  summary(value.summary, { userId, state, expectedVersion });
  check(object(value.history) && Array.isArray(value.history.records), 'history is paginated');
  check(value.history.total === applications, 'history contains exactly the committed applications');
  check(value.history.page === 1 && value.history.pageSize === 10, 'action history has default first page');
  if (applications === 0) {
    check(value.currentApplication === null && value.history.records.length === 0, 'new account has no application');
  } else {
    check(id(value.currentApplication?.id), 'current application has a safe identifier');
    check(value.currentApplication.applicationVersion === applicationVersion, 'submission sequence is independent of summary version');
    check(value.history.records[0]?.id === value.currentApplication.id, 'current application is first in history');
  }
  privateFieldsAbsent(value, { internal: !admin });
  return value;
}

async function deniedBusiness(token) {
  const options = { token, status: 403, errorCode: 'VERIFICATION_REQUIRED' };
  await request('GET', '/api/items', options);
  await request('GET', '/api/items/mine', options);
  await request('POST', '/api/items', {
    ...options, body: { title: 'Synthetic blocked item', description: 'Admission test only.', type: 'FOUND' },
  });
  await request('POST', '/api/ai/polish', { ...options, body: { content: 'Synthetic admission test.' } });
  await request('POST', '/api/ai/chat', { ...options, body: { question: 'Synthetic admission test.' } });
}

async function exactlyOneCommitted(calls) {
  // allSettled waits for BOTH network calls even if one assertion fails; finally
  // must not destroy a token while its other concurrent request is still running.
  const results = await Promise.allSettled(calls);
  const rejected = results.find((result) => result.status === 'rejected');
  if (rejected) throw rejected.reason;
  const responses = results.map((result) => result.value);
  check(responses.filter((result) => result.status === 200).length === 1, 'exactly one concurrent action commits');
  check(responses.filter((result) => result.status === 409).length === 1, 'other concurrent action conflicts');
  check(responses.find((result) => result.status === 409).errorCode === 'VERSION_CONFLICT', 'concurrent loser reports stale version');
  return responses.find((result) => result.status === 200).data;
}

async function run() {
  check(Number(process.versions.node.split('.')[0]) >= 22, 'Node.js 22 or newer');
  const adminPassword = process.env.TEST_ADMIN_PASSWORD;
  check(typeof adminPassword === 'string' && adminPassword.trim().length > 0, 'TEST_ADMIN_PASSWORD must be set explicitly');
  const suffix = randomBytes(8).toString('hex');
  const username = `identity_${suffix}`;
  const password = randomBytes(18).toString('hex');
  const validThrough = new Date(Date.now() + 7 * 86_400_000).toISOString().slice(0, 10);
  const evidenceSummary = `Synthetic test evidence ${suffix}; not an actual campus identity.`;
  const internalNote = `Synthetic confidential reviewer note ${suffix}`;
  const applicationBody = (expectedVersion) => ({
    expectedVersion, realName: 'Synthetic Identity Applicant',
    studentNumber: `TEST-${suffix}`, statement: 'Synthetic test only. No real personal data or documents.',
  });
  const approveBody = (verification) => ({
    applicationId: verification.currentApplication.id,
    expectedVersion: verification.summary.version,
    decision: 'APPROVED', method: 'IN_PERSON', evidenceSummary, validThrough, internalNote,
  });
  const sessions = new Map();
  let registrationAttempted = false;
  let userId;
  let itemId;
  let mainFailure;
  let cleanupFailed = false;
  try {
    step('API-01 public test configuration and unauthenticated boundaries');
    const config = await data('GET', '/api/public/config');
    check(object(config) && config.isTest === true, 'refuse writes outside a test environment');
    check(typeof config.timezone === 'string' && typeof config.verificationInstructions === 'string', 'manual identity guidance is public');
    check(Array.isArray(config.categories) && typeof config.aiEnabled === 'boolean', 'public feature configuration');
    privateFieldsAbsent(config);
    await request('GET', '/api/users/me', { status: 401, errorCode: 'AUTH_REQUIRED' });
    await request('GET', '/api/verifications/me', { status: 401, errorCode: 'AUTH_REQUIRED' });
    await request('GET', '/api/admin/verifications', { status: 401, errorCode: 'AUTH_REQUIRED' });
    check(await data('POST', '/api/auth/logout') === null, 'anonymous logout is idempotent');

    step('API-02 registration validation, field whitelist and duplicate conflict');
    registrationAttempted = true;
    await request('POST', '/api/auth/register', {
      body: { username, password: '中'.repeat(25), nickname: 'Synthetic' },
      status: 400, errorCode: 'VALIDATION_ERROR',
    });
    const injected = await request('POST', '/api/auth/register', {
      body: { username, password, nickname: 'Synthetic Identity User', role: 'ADMIN', verificationStatus: 'VERIFIED', isTest: false },
      status: [200, 400], errorCode: 'VALIDATION_ERROR',
    });
    if (injected.status === 400) {
      check(await data('POST', '/api/auth/register', {
        body: { username, password, nickname: 'Synthetic Identity User' },
      }) === null, 'registration returns null data');
    } else {
      check(injected.data === null, 'registration returns null data');
    }
    await request('POST', '/api/auth/register', {
      body: { username, password, nickname: 'Synthetic Duplicate' },
      status: 409, errorCode: 'USERNAME_EXISTS',
    });

    step('API-03 credential failures are indistinguishable and login is not verification');
    const wrongPassword = await request('POST', '/api/auth/login', {
      body: { username, password: 'definitely-wrong-synthetic-password' },
      status: 401, errorCode: 'INVALID_CREDENTIALS',
    });
    const unknownUsername = await request('POST', '/api/auth/login', {
      body: { username: `missing_${suffix}`, password }, status: 401, errorCode: 'INVALID_CREDENTIALS',
    });
    check(wrongPassword.message === unknownUsername.message, 'credential errors do not enumerate accounts');
    const login = await data('POST', '/api/auth/login', { body: { username, password } });
    if (typeof login?.token === 'string' && login.token.length > 0) sessions.set('user', login.token);
    check(sessions.has('user') && id(login.userId), 'login provides token and safe user ID');
    userId = login.userId;
    const userToken = sessions.get('user');
    check(login.role === 'USER' && login.username === username, 'client cannot register an administrator');
    summary(login.verification, { userId, state: 'UNVERIFIED', expectedVersion: 0 });
    privateFieldsAbsent(login.verification);

    step('API-05/06 profile versions, safe fields and clearing contact');
    const me = await data('GET', '/api/users/me', { token: userToken });
    check(me.userId === userId && me.username === username && me.role === 'USER', 'profile uses session account');
    check(version(me.version), 'profile exposes a version');
    privateFieldsAbsent(me);
    await request('PUT', '/api/users/me', {
      token: userToken, body: { nickname: 'Synthetic Missing Version', contact: null },
      status: 400, errorCode: 'VALIDATION_ERROR',
    });
    const updated = await data('PUT', '/api/users/me', {
      token: userToken, body: { nickname: 'Synthetic Updated User', contact: 'TEST-CONTACT', expectedVersion: me.version },
    });
    check(updated.nickname === 'Synthetic Updated User' && updated.contact === 'TEST-CONTACT', 'editable profile fields persist');
    check(updated.version === me.version + 1 && updated.role === 'USER', 'profile version increments without privilege change');
    await request('PUT', '/api/users/me', {
      token: userToken, body: { nickname: 'Stale edit', contact: null, expectedVersion: me.version },
      status: 409, errorCode: 'VERSION_CONFLICT',
    });
    const cleared = await data('PUT', '/api/users/me', {
      token: userToken, body: { nickname: updated.nickname, contact: null, expectedVersion: updated.version },
    });
    check(cleared.contact === null && cleared.version === updated.version + 1, 'null explicitly clears contact');
    summary(cleared.verification, { userId, state: 'UNVERIFIED', expectedVersion: 0 });

    step('unverified accounts cannot enter item or AI business');
    await deniedBusiness(userToken);

    step('administrator authority is separate from campus qualification');
    const admin = await data('POST', '/api/auth/login', { body: { username: 'admin', password: adminPassword } });
    if (typeof admin?.token === 'string' && admin.token.length > 0) sessions.set('admin', admin.token);
    check(sessions.has('admin') && id(admin.userId) && admin.role === 'ADMIN', 'existing test administrator login');
    const adminToken = sessions.get('admin');
    const adminList = await data('GET', '/api/admin/verifications?page=1&pageSize=1', { token: adminToken });
    check(Array.isArray(adminList.records), 'administrator may review without participant qualification');
    privateFieldsAbsent(adminList);
    if (admin.verification?.status !== 'VERIFIED') await deniedBusiness(adminToken);
    else console.log('INFO existing administrator is already qualified; its identity was not modified');

    step('API-09 through API-13 deny ordinary accounts and administrator self-review');
    await request('GET', '/api/admin/verifications', { token: userToken, status: 403, errorCode: 'FORBIDDEN' });
    await request('GET', `/api/admin/verifications/${userId}`, { token: userToken, status: 403, errorCode: 'FORBIDDEN' });
    for (const action of ['review', 'revoke', 'reopen']) {
      const body = action === 'review'
        ? { applicationId: 1, expectedVersion: 0, decision: 'REJECTED', reason: 'Synthetic forbidden action' }
        : { expectedVersion: 0, reason: 'Synthetic forbidden action' };
      await request('POST', `/api/admin/verifications/${userId}/${action}`, {
        token: userToken, body, status: 403, errorCode: 'FORBIDDEN',
      });
      await request('POST', `/api/admin/verifications/${admin.userId}/${action}`, {
        token: adminToken, body, status: 403, errorCode: 'FORBIDDEN',
      });
    }

    step('API-07 empty self history and pagination validation');
    detail(await data('GET', '/api/verifications/me', { token: userToken }), {
      userId, state: 'UNVERIFIED', expectedVersion: 0, applications: 0, applicationVersion: 0,
    });
    for (const query of ['page=0', 'pageSize=51']) {
      await request('GET', `/api/verifications/me?${query}`, { token: userToken, status: 400, errorCode: 'VALIDATION_ERROR' });
      await request('GET', `/api/admin/verifications?${query}`, { token: adminToken, status: 400, errorCode: 'VALIDATION_ERROR' });
    }

    step('API-08 submit once and prevent pending or stale resubmission');
    await request('POST', '/api/verifications/me', {
      token: userToken, body: { realName: 'Synthetic Missing Version' }, status: 400, errorCode: 'VALIDATION_ERROR',
    });
    const pending = detail(await data('POST', '/api/verifications/me', {
      token: userToken, body: applicationBody(0),
    }), { userId, state: 'PENDING', expectedVersion: 1, applications: 1, applicationVersion: 1 });
    check(pending.currentApplication.status === 'PENDING', 'first application awaits manual review');
    await request('POST', '/api/verifications/me', {
      token: userToken, body: applicationBody(0), status: 409, errorCode: 'VERSION_CONFLICT',
    });
    await request('POST', '/api/verifications/me', {
      token: userToken, body: applicationBody(1), status: 409, errorCode: 'STATE_CONFLICT',
    });

    step('API-09/10 filtered management list and isolated detail');
    const filtered = await data('GET', `/api/admin/verifications?userId=${userId}&status=PENDING&keyword=${username}`, { token: adminToken });
    check(filtered.total === 1 && filtered.records.length === 1 && filtered.records[0].userId === userId, 'management filters identify only the synthetic target');
    privateFieldsAbsent(filtered);
    const internal = detail(await data('GET', `/api/admin/verifications/${userId}`, { token: adminToken }), {
      userId, state: 'PENDING', expectedVersion: 1, applications: 1, applicationVersion: 1, admin: true,
    });
    check(internal.summary.currentApplicationId === pending.currentApplication.id, 'summary points to the current application');

    step('API-11 requires review evidence, future expiry, current application and current version');
    await request('POST', `/api/admin/verifications/${userId}/review`, {
      token: adminToken, body: { ...approveBody(pending), evidenceSummary: '' }, status: 400, errorCode: 'VALIDATION_ERROR',
    });
    await request('POST', `/api/admin/verifications/${userId}/review`, {
      token: adminToken, body: { ...approveBody(pending), validThrough: '2000-01-01' }, status: 400, errorCode: 'VALIDATION_ERROR',
    });
    await request('POST', `/api/admin/verifications/${userId}/review`, {
      token: adminToken, body: { ...approveBody(pending), expectedVersion: 0 }, status: 409, errorCode: 'VERSION_CONFLICT',
    });
    await request('POST', `/api/admin/verifications/${userId}/review`, {
      token: adminToken, body: { ...approveBody(pending), applicationId: pending.currentApplication.id + 1_000_000 },
      status: 409, errorCode: ['STATE_CONFLICT', 'VERSION_CONFLICT'],
    });

    step('manual rejection is visible but internal notes remain private');
    const rejected = detail(await data('POST', `/api/admin/verifications/${userId}/review`, {
      token: adminToken,
      body: { applicationId: pending.currentApplication.id, expectedVersion: 1, decision: 'REJECTED', reason: 'Synthetic: please resubmit the test fixture.', internalNote },
    }), { userId, state: 'REJECTED', expectedVersion: 2, applications: 1, applicationVersion: 1, admin: true });
    check(rejected.currentApplication.status === 'REJECTED', 'rejection preserves the historical application');
    check(rejected.currentApplication.internalNote === internalNote, 'internal rejection note stored for administrators');
    const selfRejected = detail(await data('GET', '/api/verifications/me', { token: userToken }), {
      userId, state: 'REJECTED', expectedVersion: 2, applications: 1, applicationVersion: 1,
    });
    check(selfRejected.currentApplication.reason === rejected.currentApplication.reason, 'applicant receives the rejection reason');

    step('resubmit and reject approvals for the previous application ID');
    const resubmitted = detail(await data('POST', '/api/verifications/me', { token: userToken, body: applicationBody(2) }), {
      userId, state: 'PENDING', expectedVersion: 3, applications: 2, applicationVersion: 2,
    });
    check(resubmitted.currentApplication.id !== pending.currentApplication.id, 'resubmission creates a new application');
    await request('POST', `/api/admin/verifications/${userId}/review`, {
      token: adminToken, body: { ...approveBody(resubmitted), applicationId: pending.currentApplication.id },
      status: 409, errorCode: ['STATE_CONFLICT', 'VERSION_CONFLICT'],
    });

    step('concurrent manual approvals commit once');
    const approved = detail(await exactlyOneCommitted([0, 1].map(() => request('POST', `/api/admin/verifications/${userId}/review`, {
      token: adminToken, body: approveBody(resubmitted), status: [200, 409], errorCode: 'VERSION_CONFLICT',
    }))), { userId, state: 'VERIFIED', expectedVersion: 4, applications: 2, applicationVersion: 2, admin: true });
    check(approved.currentApplication.status === 'VERIFIED', 'approval is recorded in immutable application history');
    check(approved.currentApplication.reviewerId === admin.userId, 'review actor is server-controlled');
    check(approved.currentApplication.evidenceSummary === evidenceSummary && approved.currentApplication.internalNote === internalNote, 'administrator can read private review evidence');
    check(approved.summary.validThrough === validThrough && Date.parse(approved.summary.expiresAt) > Date.now(), 'approval has an effective expiry');
    const expiryDate = new Intl.DateTimeFormat('en-CA', { timeZone: config.timezone, year: 'numeric', month: '2-digit', day: '2-digit' })
      .format(new Date(approved.summary.expiresAt));
    check(expiryDate === new Date(Date.parse(`${validThrough}T00:00:00Z`) + 86_400_000).toISOString().slice(0, 10), 'expiry is the next calendar day in campus timezone');
    const expiryClock = new Intl.DateTimeFormat('en-GB', { timeZone: config.timezone, hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' })
      .format(new Date(approved.summary.expiresAt));
    check(expiryClock === '00:00:00', 'expiry is exclusive campus midnight');
    const selfApproved = detail(await data('GET', '/api/verifications/me', { token: userToken }), {
      userId, state: 'VERIFIED', expectedVersion: 4, applications: 2, applicationVersion: 2,
    });
    check(selfApproved.history.records[1].status === 'REJECTED', 'earlier rejected history is unchanged');

    step('existing ordinary session gains business access after approval');
    check(Array.isArray(await data('GET', '/api/items', { token: userToken })), 'same token can read item business');
    const created = await data('POST', '/api/items', {
      token: userToken, body: { title: `IDENTITY_${suffix}`, description: 'Synthetic identity admission fixture.', type: 'FOUND', category: 'TEST_ONLY', location: 'TEST_ONLY' },
    });
    check(id(created?.id) && created.publisherId === userId && created.status === 'PENDING', 'qualified ordinary account can publish');
    itemId = created.id;

    step('API-12 revocation respects versions and preserves past approval');
    await request('POST', `/api/admin/verifications/${userId}/revoke`, {
      token: adminToken, body: { expectedVersion: 3, reason: 'Synthetic stale revocation' }, status: 409, errorCode: 'VERSION_CONFLICT',
    });
    const revoked = detail(await data('POST', `/api/admin/verifications/${userId}/revoke`, {
      token: adminToken, body: { expectedVersion: 4, reason: 'Synthetic revocation for integration test.' },
    }), { userId, state: 'REVOKED', expectedVersion: 5, applications: 2, applicationVersion: 2, admin: true });
    check(revoked.currentApplication.status === 'VERIFIED', 'revocation does not rewrite historical approval');
    await deniedBusiness(userToken);
    const revokedMe = await data('GET', '/api/users/me', { token: userToken });
    summary(revokedMe.verification, { userId, state: 'REVOKED', expectedVersion: 5 });
    privateFieldsAbsent(revokedMe);
    await request('POST', '/api/verifications/me', {
      token: userToken, body: applicationBody(5), status: 409, errorCode: 'STATE_CONFLICT',
    });

    step('API-13 reopening grants resubmission, not business access');
    await request('POST', `/api/admin/verifications/${userId}/reopen`, {
      token: adminToken, body: { expectedVersion: 4, reason: 'Synthetic stale reopen' }, status: 409, errorCode: 'VERSION_CONFLICT',
    });
    const reopened = detail(await data('POST', `/api/admin/verifications/${userId}/reopen`, {
      token: adminToken, body: { expectedVersion: 5, reason: 'Synthetic permission to submit a new test fixture.' },
    }), { userId, state: 'UNVERIFIED', expectedVersion: 6, applications: 2, applicationVersion: 2, admin: true });
    check(reopened.currentApplication.status === 'VERIFIED', 'reopening preserves historical approval');
    await deniedBusiness(userToken);

    step('concurrent resubmissions create exactly one next application');
    const pendingAgain = detail(await exactlyOneCommitted([0, 1].map(() => request('POST', '/api/verifications/me', {
      token: userToken, body: applicationBody(6), status: [200, 409], errorCode: 'VERSION_CONFLICT',
    }))), { userId, state: 'PENDING', expectedVersion: 7, applications: 3, applicationVersion: 3 });
    detail(await data('POST', `/api/admin/verifications/${userId}/review`, { token: adminToken, body: approveBody(pendingAgain) }), {
      userId, state: 'VERIFIED', expectedVersion: 8, applications: 3, applicationVersion: 3, admin: true,
    });

    step('paginated self history remains private after repeated reviews');
    const pageOne = await data('GET', '/api/verifications/me?page=1&pageSize=1', { token: userToken });
    const pageTwo = await data('GET', '/api/verifications/me?page=2&pageSize=1', { token: userToken });
    check(pageOne.history.total === 3 && pageOne.history.records.length === 1 && pageOne.history.pageSize === 1, 'self history paginates three committed submissions');
    check(pageOne.history.records[0].applicationVersion === 3 && pageTwo.history.records[0].applicationVersion === 2, 'history ordering is stable across pages');
    check(pageTwo.currentApplication.applicationVersion === 3, 'history pagination never changes the current application');
    privateFieldsAbsent(pageOne);
    privateFieldsAbsent(pageTwo);
    const mine = await data('GET', '/api/items/mine', { token: userToken });
    check(Array.isArray(mine) && mine.some((item) => item.id === itemId), 'reverified session regains access to its existing item');

    step('API-04 logout invalidates only the current token and remains idempotent');
    check(await data('POST', '/api/auth/logout', { token: userToken }) === null, 'logout returns null');
    await request('GET', '/api/users/me', { token: userToken, status: 401, errorCode: 'AUTH_REQUIRED' });
    await request('GET', '/api/verifications/me', { token: userToken, status: 401, errorCode: 'AUTH_REQUIRED' });
    await request('GET', '/api/items/mine', { token: userToken, status: 401, errorCode: 'AUTH_REQUIRED' });
    check(await data('POST', '/api/auth/logout', { token: userToken }) === null, 'repeated logout succeeds');
    const stillAdmin = await data('GET', '/api/users/me', { token: adminToken });
    check(stillAdmin.userId === admin.userId, 'other session remains valid');
  } catch (error) {
    mainFailure = { step: currentStep, label: error instanceof CheckFailure ? error.message : 'unexpected local test failure' };
  } finally {
    for (const [kind, token] of sessions) {
      try {
        check(await data('POST', '/api/auth/logout', { token }) === null, 'own-session cleanup succeeds');
      } catch {
        cleanupFailed = true;
        console.error(`FAIL finally: logout own ${kind} session`);
      }
    }
    sessions.clear();
    if (registrationAttempted) console.log(`RECORD_CANDIDATE username=${username}`);
    if (id(userId)) console.log(`RECORD userId=${userId}`);
    if (id(itemId)) console.log(`RECORD itemId=${itemId}`);
  }
  if (mainFailure) console.error(`FAIL ${mainFailure.step}: ${mainFailure.label}`);
  console.log(`${mainFailure || cleanupFailed ? 'FAIL' : 'PASS'} identity assertions=${assertions}`);
  if (mainFailure || cleanupFailed) process.exitCode = 1;
}

const args = process.argv.slice(2);
if (args.length === 1 && args[0] === '--help') console.log(HELP);
else if (args.length !== 1 || args[0] !== '--confirm-test-environment') {
  console.error('FAIL preflight: use --help or the explicit --confirm-test-environment flag');
  process.exitCode = 2;
} else {
  try {
    await run();
  } catch (error) {
    console.error(`FAIL preflight: ${error instanceof CheckFailure ? error.message : 'local setup failed'}`);
    process.exitCode = 1;
  }
}
