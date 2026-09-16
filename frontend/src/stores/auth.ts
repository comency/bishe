import { computed, shallowRef, ref } from 'vue'
import { defineStore } from 'pinia'
import { browserSessionStorage, parseSession, persistSession, restoreSession } from '../lib/session'
import { createRequest, ApiError } from '../lib/request'
import { assertUserMe, effectiveStatus, type UserMe, type VerificationSummary } from '../lib/identity'

export const useAuthStore = defineStore('auth', () => {
  const storage = browserSessionStorage()
  const session = shallowRef(restoreSession(storage))
  const notice = ref('')
  const me = shallowRef<UserMe | null>(null)
  const revision = ref(0)
  const now = ref(Date.now())
  let refreshSequence = 0
  const hasSession = computed(() => session.value !== null)
  const canUseBusiness = computed(() => !!me.value && effectiveStatus(me.value.verification, now.value) === 'VERIFIED')
  const isAdmin = computed(() => me.value?.role === 'ADMIN')
  const client = createRequest({
    getToken: () => session.value?.token,
    getSessionRevision: () => revision.value,
    onUnauthorized: () => clear('登录已失效，请重新登录。'),
    onVerificationRequired: () => requireVerification('当前校园资格无效，请查看认证结果。'),
  })

  function signIn(value: unknown) {
    const parsed = parseSession(value)
    if (!parsed) throw new Error('登录响应格式不正确，请检查后端接口。')
    session.value = parsed
    me.value = null
    revision.value++
    refreshSequence++
    notice.value = ''
    persistSession(parsed, storage)
  }

  function clear(message = '') {
    notice.value = message
    session.value = null
    me.value = null
    revision.value++
    refreshSequence++
    persistSession(null, storage)
  }

  function requireVerification(message: string) {
    notice.value = message
    me.value = null
    revision.value++
    refreshSequence++
  }

  function applyMe(value: unknown) {
    assertUserMe(value)
    if (!session.value || value.userId !== session.value.userId) throw new ApiError('会话与本人资料不一致。')
    refreshSequence++
    me.value = value
    now.value = Date.now()
    session.value = { ...session.value, nickname: value.nickname, role: value.role }
    persistSession(session.value, storage)
  }

  async function refreshMe(signal?: AbortSignal) {
    const sequence = ++refreshSequence
    const value = await client<unknown>('/api/users/me', { signal })
    if (sequence !== refreshSequence) throw new ApiError('较早的资格查询已作废，请重新查询。')
    applyMe(value)
    return me.value!
  }

  function applyVerification(summary: VerificationSummary) {
    refreshSequence++
    if (me.value && summary.userId === me.value.userId) me.value = { ...me.value, verification: summary }
    now.value = Date.now()
  }

  function checkExpiry() {
    const previouslyValid = canUseBusiness.value
    now.value = Date.now()
    if (previouslyValid && !canUseBusiness.value) {
      notice.value = '校园认证已到期，请重新申请人工核验。'
      revision.value++
      refreshSequence++
    }
  }

  return { session, hasSession, notice, me, canUseBusiness, isAdmin, revision, now,
    client, signIn, clear, refreshMe, applyMe, applyVerification, requireVerification, checkExpiry }
})
