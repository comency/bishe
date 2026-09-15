export class ApiError extends Error {
  constructor(message: string, public readonly status = 0, public readonly code?: number) {
    super(message)
    this.name = 'ApiError'
  }
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  body?: unknown
  auth?: boolean
  signal?: AbortSignal
  timeoutMs?: number
}

interface ClientOptions {
  getToken: () => string | undefined
  onUnauthorized: () => void
  fetcher?: typeof fetch
}

function isEnvelope(value: unknown): value is { code: number; message: string; data: unknown } {
  return !!value && typeof value === 'object' &&
    typeof (value as Record<string, unknown>).code === 'number' &&
    typeof (value as Record<string, unknown>).message === 'string' && 'data' in value
}

export function createRequest(client: ClientOptions) {
  return async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
    // Keep tokens on same-origin API calls; never accept a full/external URL.
    if (!path.startsWith('/api/') || /[\\\r\n]/.test(path)) throw new ApiError('接口路径不合法。')
    const authenticated = options.auth !== false
    const token = authenticated ? client.getToken() : undefined
    const controller = new AbortController()
    let timedOut = false
    const abort = () => controller.abort()
    if (options.signal?.aborted) controller.abort()
    options.signal?.addEventListener('abort', abort, { once: true })
    const timeout = setTimeout(() => { timedOut = true; controller.abort() }, options.timeoutMs ?? 10000)

    try {
      const response = await (client.fetcher ?? fetch)(path, {
        method: options.method ?? 'GET',
        headers: {
          Accept: 'application/json',
          ...(options.body !== undefined ? { 'Content-Type': 'application/json' } : {}),
          ...(token ? { 'X-Token': token } : {}),
        },
        body: options.body === undefined ? undefined : JSON.stringify(options.body),
        credentials: 'same-origin',
        cache: 'no-store',
        redirect: 'error',
        signal: controller.signal,
      })
      const payload: unknown = await response.json().catch(error => {
        if (controller.signal.aborted) throw error
        return null
      })
      if (controller.signal.aborted) throw new Error('Request aborted while reading response')
      // A late response from a previous account must not clear or populate the new account.
      if (authenticated && token !== client.getToken()) throw new ApiError('会话已切换，请重新查询。')
      const invalidSession = response.status === 401 || (isEnvelope(payload) && payload.code === 401)
      if (invalidSession) {
        if (authenticated) client.onUnauthorized()
        throw new ApiError('登录已失效，请重新登录。', 401, isEnvelope(payload) ? payload.code : undefined)
      }
      if (!response.ok) {
        const fallback = response.status === 403 ? '当前账号无权访问。' : `请求失败（HTTP ${response.status}），请稍后重试。`
        throw new ApiError(isEnvelope(payload) ? payload.message || fallback : fallback, response.status)
      }
      if (!isEnvelope(payload)) throw new ApiError('接口返回格式不正确，请检查后端服务。', response.status)
      if (payload.code !== 0) throw new ApiError(payload.message || '操作未成功，请检查输入。', response.status, payload.code)
      return payload.data as T
    } catch (error) {
      if (error instanceof ApiError) throw error
      if (controller.signal.aborted) {
        if (!timedOut) throw new ApiError('请求已取消。')
        const message = options.method && options.method !== 'GET'
          ? '请求超时，操作结果待确认。请先查询结果，不要重复提交。'
          : '请求超时，请检查后端服务后重试。'
        throw new ApiError(message)
      }
      throw new ApiError('暂时无法连接后端服务，请确认后端已启动。')
    } finally {
      clearTimeout(timeout)
      options.signal?.removeEventListener('abort', abort)
    }
  }
}
