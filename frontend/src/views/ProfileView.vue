<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useAuthStore } from '../stores/auth'
import { ApiError } from '../lib/request'

const auth = useAuthStore()
const nickname = ref('')
const contact = ref('')
const version = ref<number | null>(null)
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const success = ref('')
const controller = new AbortController()
let sequence = 0
onBeforeUnmount(() => { sequence++; controller.abort(); nickname.value = ''; contact.value = '' })

async function load() {
  const current = ++sequence
  loading.value = true
  version.value = null
  error.value = ''
  try {
    const me = await auth.refreshMe(controller.signal)
    if (current !== sequence) return
    nickname.value = me.nickname
    contact.value = me.contact ?? ''
    version.value = me.version
  } catch (failure) { if (current === sequence) error.value = failure instanceof Error ? failure.message : '资料读取失败。' }
  finally { if (current === sequence) loading.value = false }
}

async function save() {
  if (saving.value || version.value === null) return
  error.value = ''; success.value = ''
  if (!nickname.value.trim()) { error.value = '昵称不能为空。'; return }
  saving.value = true
  try {
    const value = await auth.client('/api/users/me', { method: 'PUT', signal: controller.signal,
      body: { nickname: nickname.value.trim(), contact: contact.value.trim() || null, expectedVersion: version.value } })
    auth.applyMe(value)
    version.value = auth.me!.version
    nickname.value = auth.me!.nickname
    contact.value = auth.me!.contact ?? ''
    success.value = '本人资料已保存，当前联系方式仅在本人资料中展示。'
  } catch (failure) {
    if (controller.signal.aborted) return
    if (failure instanceof ApiError && failure.status === 409) {
      await load()
      error.value = '资料已被更新，已重新读取最新版本。请核对后重新填写并保存。'
    } else error.value = failure instanceof Error ? failure.message : '保存失败，请重试。'
  } finally { saving.value = false }
}
onMounted(load)
</script>

<template>
  <section class="identity-page">
    <div class="page-heading"><div><span class="eyebrow">YOUR CAMPUS ACCOUNT</span><h1>本人资料</h1><p class="muted">维护昵称与日常联系方式。</p></div></div>
    <div class="surface narrow-surface">
      <p v-if="loading" class="muted" role="status">正在读取最新资料…</p>
      <p v-if="error" class="error-message" role="alert">{{ error }}</p>
      <p v-if="success" class="notice" role="status">{{ success }}</p>
      <form v-if="version !== null" class="auth-form" @submit.prevent="save">
        <dl class="detail-grid"><div><dt>用户名</dt><dd>{{ auth.me?.username }}</dd></div><div><dt>账号角色</dt><dd>{{ auth.me?.role === 'ADMIN' ? '管理员' : '普通账号' }}</dd></div></dl>
        <label for="profile-nickname">昵称</label><input id="profile-nickname" v-model="nickname" required maxlength="255" autocomplete="nickname" :disabled="saving" />
        <label for="profile-contact">联系方式 <small>选填，最多 100 个字符</small></label><input id="profile-contact" v-model="contact" maxlength="100" :disabled="saving" />
        <p class="field-help">清空后保存可移除当前联系方式。用户名、角色和认证结果不能在此修改。</p>
        <div class="button-row"><button class="primary-button" :disabled="saving" type="submit">{{ saving ? '保存中…' : '保存资料' }}</button><button class="secondary-button" type="button" :disabled="saving || loading" @click="load">刷新最新资料</button></div>
      </form>
      <button v-else-if="!loading" class="secondary-button" @click="load">重新读取</button>
    </div>
  </section>
</template>
