import { computed, shallowRef, ref } from 'vue'
import { defineStore } from 'pinia'
import { browserSessionStorage, parseSession, persistSession, restoreSession } from '../lib/session'

export const useAuthStore = defineStore('auth', () => {
  const storage = browserSessionStorage()
  const session = shallowRef(restoreSession(storage))
  const notice = ref('')
  const hasSession = computed(() => session.value !== null)

  function signIn(value: unknown) {
    const parsed = parseSession(value)
    if (!parsed) throw new Error('登录响应格式不正确，请检查后端接口。')
    session.value = parsed
    notice.value = ''
    persistSession(parsed, storage)
  }

  function clear(message = '') {
    notice.value = message
    session.value = null
    persistSession(null, storage)
  }

  return { session, hasSession, notice, signIn, clear }
})
