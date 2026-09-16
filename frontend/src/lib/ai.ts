import { computed, onScopeDispose, ref } from 'vue'
import type { RequestOptions } from './request'
export type AiReason = 'DISABLED' | 'RESOURCE_LIMIT' | 'BUSY' | 'TIMEOUT' | 'UPSTREAM_ERROR' | 'EMPTY_RESULT'
export type AiResult = { content: string; status: 'GENERATED'; reason: null } | { content: string; status: 'UNAVAILABLE'; reason: AiReason }
export const aiReasons: Record<AiReason, string> = { DISABLED: '未启用', RESOURCE_LIMIT: '资源或容量不足', BUSY: '繁忙', TIMEOUT: '生成超时', UPSTREAM_ERROR: '服务不可用', EMPTY_RESULT: '未获得有效结果' }
export function validAiResult(value: unknown): value is AiResult {
  if (!value || typeof value !== 'object') return false
  const r = value as Partial<AiResult>
  return typeof r.content === 'string' && r.content.length <= 12000 &&
    ((r.status === 'GENERATED' && r.reason === null && !!r.content.trim()) || (r.status === 'UNAVAILABLE' && typeof r.reason === 'string' && Object.hasOwn(aiReasons, r.reason)))
}
type Client = <T>(path: string, options?: RequestOptions) => Promise<T>
/** Request snapshots, not live input. No auto-apply, retry, persistent history or HTML rendering. */
export function useAiRequest(kind: 'polish' | 'chat', input: () => string, client: Client) {
  const result = ref<AiResult | null>(null), snapshot = ref(''), busy = ref(false), error = ref('')
  let sequence = 0, controller = new AbortController()
  const canApply = computed(() => kind === 'polish' && !busy.value && result.value?.status === 'GENERATED' && result.value.content.length <= 3000 && input() === snapshot.value)
  async function run() {
    const current = ++sequence; controller.abort(); controller = new AbortController(); result.value = null; error.value = ''; snapshot.value = input()
    if (!snapshot.value.trim() || snapshot.value.length > 3000) { error.value = '请输入1–3000字符的非空文本。'; busy.value = false; return }
    busy.value = true
    try {
      const data = await client<unknown>(`/api/ai/${kind}`, { method: 'POST', body: kind === 'polish' ? { content: snapshot.value } : { question: snapshot.value }, signal: controller.signal, timeoutMs: 25000 })
      if (current !== sequence) return
      if (!validAiResult(data)) throw new Error('辅助结果格式无效，原文未改变。')
      result.value = data
    } catch (failure) { if (current === sequence) error.value = failure instanceof Error ? failure.message : '智能辅助暂不可用，原文未改变。' }
    finally { if (current === sequence) busy.value = false }
  }
  function clear() { ++sequence; controller.abort(); busy.value = false; result.value = null; snapshot.value = ''; error.value = '' }
  onScopeDispose(clear)
  return { result, snapshot, busy, error, canApply, run, clear }
}
