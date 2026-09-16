import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, createRequest } from '../src/lib/request'

function jsonResponse(value: unknown, status = 200) {
  return new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
}
function makeClient(response: Response) {
  const fetcher = vi.fn<typeof fetch>().mockResolvedValue(response)
  const onUnauthorized = vi.fn()
  const request = createRequest({ getToken: () => 'unit-test-token', onUnauthorized, fetcher })
  return { request, fetcher, onUnauthorized }
}

afterEach(() => vi.useRealTimers())

describe('API client', () => {
  it('uses the existing X-Token contract and only unwraps code 0', async () => {
    const { request, fetcher } = makeClient(jsonResponse({ code: 0, message: 'success', data: [1] }))
    expect(await request('/api/items')).toEqual([1])
    expect(fetcher.mock.calls[0]?.[1]).toMatchObject({ headers: { 'X-Token': 'unit-test-token' }, redirect: 'error', cache: 'no-store' })
  })

  it('does not send an existing token with guest login or registration', async () => {
    const { request, fetcher } = makeClient(jsonResponse({ code: 0, message: 'success', data: null }))
    await request('/api/auth/register', { method: 'POST', auth: false, body: { username: 'test' } })
    expect(fetcher.mock.calls[0]?.[1]?.headers).not.toHaveProperty('X-Token')
    expect(fetcher.mock.calls[0]?.[1]?.body).toBe('{"username":"test"}')
  })

  it('rejects HTTP 200 with a nonzero business code', async () => {
    const { request } = makeClient(jsonResponse({ code: -1, message: '业务失败', data: null }))
    await expect(request('/api/items')).rejects.toMatchObject({ message: '业务失败', status: 200, code: -1 })
  })

  it('rejects a failed HTTP status even if the envelope has code 0', async () => {
    const { request } = makeClient(jsonResponse({ code: 0, message: '服务器故障', data: null }, 500))
    await expect(request('/api/items')).rejects.toMatchObject({ status: 500 })
  })

  it.each([null, {}, { code: 0, data: [] }, { code: '0', message: 'success', data: [] }])('rejects a malformed successful response: %j', async payload => {
    const { request } = makeClient(jsonResponse(payload))
    await expect(request('/api/items')).rejects.toBeInstanceOf(ApiError)
  })

  it('invalidates the session on HTTP 401 even when the body is not JSON', async () => {
    const { request, onUnauthorized } = makeClient(new Response('not JSON', { status: 401 }))
    await expect(request('/api/items')).rejects.toMatchObject({ status: 401 })
    expect(onUnauthorized).toHaveBeenCalledOnce()
  })

  it('invalidates an explicit business 401 but not an ordinary login failure', async () => {
    const { request, onUnauthorized } = makeClient(jsonResponse({ code: 401, message: '失效', data: null }))
    await expect(request('/api/items')).rejects.toMatchObject({ status: 401 })
    expect(onUnauthorized).toHaveBeenCalledOnce()
    const guest = makeClient(jsonResponse({ code: -1, message: '登录失败', data: null }, 401))
    await expect(guest.request('/api/auth/login', { auth: false })).rejects.toMatchObject({ status: 401 })
    expect(guest.onUnauthorized).not.toHaveBeenCalled()
  })

  it('does not clear a valid session on 403', async () => {
    const { request, onUnauthorized } = makeClient(jsonResponse({ code: -1, message: '无权限', data: null }, 403))
    await expect(request('/api/items')).rejects.toMatchObject({ status: 403, message: '无权限' })
    expect(onUnauthorized).not.toHaveBeenCalled()
  })

  it.each(['https://example.com/api/items', '//example.com/api/items', '/outside', '/api/\\example.com'])('refuses non-API or unsafe paths: %s', async path => {
    const { request, fetcher } = makeClient(jsonResponse({ code: 0, message: 'success', data: [] }))
    await expect(request(path)).rejects.toThrow('接口路径不合法')
    expect(fetcher).not.toHaveBeenCalled()
  })

  it.each([200, 401])('discards a stale response without affecting the next account (HTTP %s)', async status => {
    let token = 'old-token'
    const onUnauthorized = vi.fn()
    const request = createRequest({
      getToken: () => token, onUnauthorized,
      fetcher: vi.fn<typeof fetch>().mockImplementation(async () => {
        token = 'new-token'
        return jsonResponse({ code: 0, message: 'success', data: ['private old data'] }, status)
      }),
    })
    await expect(request('/api/items')).rejects.toThrow('会话或资格已变化')
    expect(onUnauthorized).not.toHaveBeenCalled()
  })

  it('shows a connection failure without converting it into successful empty data', async () => {
    const request = createRequest({ getToken: () => undefined, onUnauthorized: vi.fn(), fetcher: vi.fn<typeof fetch>().mockRejectedValue(new TypeError('offline')) })
    await expect(request('/api/items')).rejects.toThrow('暂时无法连接后端服务')
  })

  it('distinguishes eligibility 403 from object permissions and retains trace context', async () => {
    const onVerificationRequired = vi.fn()
    const onUnauthorized = vi.fn()
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse({ code: -1, message: '需认证', data: null, errorCode: 'VERIFICATION_REQUIRED', traceId: 'synthetic-trace' }, 403))
      .mockResolvedValueOnce(jsonResponse({ code: -1, message: '无对象权限', data: null, errorCode: 'FORBIDDEN' }, 403))
    const request = createRequest({ getToken: () => 'same-token', onUnauthorized, onVerificationRequired, fetcher })
    await expect(request('/api/items')).rejects.toMatchObject({ status: 403, errorCode: 'VERIFICATION_REQUIRED', traceId: 'synthetic-trace' })
    expect(onVerificationRequired).toHaveBeenCalledOnce()
    await expect(request('/api/admin/verifications')).rejects.toMatchObject({ errorCode: 'FORBIDDEN' })
    expect(onVerificationRequired).toHaveBeenCalledOnce()
    expect(onUnauthorized).not.toHaveBeenCalled()
  })

  it('rejects a late business response after eligibility changes, even with the same token', async () => {
    let revision = 1
    const request = createRequest({ getToken: () => 'same-token', getSessionRevision: () => revision, onUnauthorized: vi.fn(),
      fetcher: vi.fn<typeof fetch>().mockImplementation(async () => { revision++; return jsonResponse({ code: 0, message: 'success', data: ['old private data'] }) }) })
    await expect(request('/api/items')).rejects.toThrow('会话或资格已变化')
  })

  it('reports a 409 as requiring a fresh read, without replaying the request', async () => {
    const { request, fetcher } = makeClient(jsonResponse({ code: -1, message: '版本已变化', data: null, errorCode: 'VERSION_CONFLICT' }, 409))
    await expect(request('/api/users/me', { method: 'PUT', body: { expectedVersion: 0 } })).rejects.toMatchObject({ status: 409, errorCode: 'VERSION_CONFLICT' })
    expect(fetcher).toHaveBeenCalledOnce()
  })

  it('aborts on timeout and warns that write results remain unknown, with no retries', async () => {
    vi.useFakeTimers()
    const fetcher = vi.fn<typeof fetch>().mockImplementation((_url, init) => new Promise((_resolve, reject) => {
      init?.signal?.addEventListener('abort', () => reject(new Error('aborted')), { once: true })
    }))
    const request = createRequest({ getToken: () => undefined, onUnauthorized: vi.fn(), fetcher })
    const pending = expect(request('/api/auth/register', { method: 'POST', timeoutMs: 20 })).rejects.toThrow('操作结果待确认')
    await vi.advanceTimersByTimeAsync(21)
    await pending
    expect(fetcher).toHaveBeenCalledOnce()
  })

  it('propagates page cancellation without reporting a timeout', async () => {
    const controller = new AbortController()
    const fetcher = vi.fn<typeof fetch>().mockImplementation((_url, init) => new Promise((_resolve, reject) => {
      init?.signal?.addEventListener('abort', () => reject(new Error('aborted')), { once: true })
    }))
    const request = createRequest({ getToken: () => undefined, onUnauthorized: vi.fn(), fetcher })
    const pending = expect(request('/api/items', { signal: controller.signal })).rejects.toThrow('请求已取消')
    controller.abort()
    await pending
  })

  it('preserves a timeout while reading the response body instead of reporting invalid JSON', async () => {
    vi.useFakeTimers()
    const fetcher = vi.fn<typeof fetch>().mockImplementation(async (_url, init) => {
      const response = jsonResponse({ code: 0, message: 'success', data: [] })
      vi.spyOn(response, 'json').mockImplementation(() => new Promise((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => reject(new Error('body aborted')), { once: true })
      }))
      return response
    })
    const request = createRequest({ getToken: () => undefined, onUnauthorized: vi.fn(), fetcher })
    const pending = expect(request('/api/items', { timeoutMs: 20 })).rejects.toThrow('请求超时')
    await vi.advanceTimersByTimeAsync(21)
    await pending
  })
})
