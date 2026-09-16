import { defineStore } from 'pinia'
import { ref, shallowRef } from 'vue'
import { createRequest } from '../lib/request'
import type { PublicConfig } from '../lib/identity'

export const useConfigStore = defineStore('public-config', () => {
  const config = shallowRef<PublicConfig | null>(null)
  const error = ref('')
  const loading = ref(false)
  const client = createRequest({ getToken: () => undefined, onUnauthorized: () => {} })
  async function load() {
    if (loading.value) return
    loading.value = true
    error.value = ''
    try { config.value = await client<PublicConfig>('/api/public/config', { auth: false }) }
    catch (failure) { error.value = failure instanceof Error ? failure.message : '无法读取校园配置。' }
    finally { loading.value = false }
  }
  return { config, error, loading, load }
})
