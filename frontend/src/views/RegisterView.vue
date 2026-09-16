<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { request } from '../api'
import { safeReturnTo } from '../router'
import PublicInstructions from '../components/PublicInstructions.vue'

const router = useRouter()
const route = useRoute()
const username = ref('')
const nickname = ref('')
const password = ref('')
const confirmation = ref('')
const error = ref('')
const busy = ref(false)
const controller = new AbortController()
onBeforeUnmount(() => { controller.abort(); password.value = ''; confirmation.value = '' })

async function submit() {
  if (busy.value) return
  error.value = ''
  if (username.value.trim().length < 3 || !nickname.value.trim()) { error.value = '用户名至少 3 个字符，昵称不能为空。'; return }
  if (password.value.length < 6 || password.value.length > 64 || new TextEncoder().encode(password.value).length > 72) {
    error.value = '密码需为 6–64 个字符，且 UTF-8 编码不超过 72 字节。'; return
  }
  if (password.value !== confirmation.value) { error.value = '两次输入的密码不一致。'; return }
  busy.value = true
  try {
    await request('/api/auth/register', {
      method: 'POST', auth: false, signal: controller.signal,
      body: { username: username.value.trim(), nickname: nickname.value.trim(), password: password.value },
    })
    if (controller.signal.aborted) return
    password.value = ''; confirmation.value = ''
    await router.replace({ name: 'login', query: { registered: '1', returnTo: safeReturnTo(route.query.returnTo) } })
  } catch (failure) {
    if (!controller.signal.aborted) error.value = failure instanceof Error ? failure.message : '注册失败，请稍后重试。'
  } finally { busy.value = false }
}
</script>

<template>
  <section class="register-page">
    <div class="auth-card register-card">
      <RouterLink to="/login" class="back-link">← 返回登录</RouterLink>
      <span class="tiny-label">加入校园互助</span><h1>注册一个新账号</h1>
      <p class="muted">仅用于本地开发测试，请使用合成信息。</p>
      <PublicInstructions />
      <form class="auth-form" @submit.prevent="submit">
        <label for="register-username">用户名 <small>3–40 个字符</small></label>
        <input id="register-username" v-model="username" required minlength="3" maxlength="40" autocomplete="username" :disabled="busy" />
        <label for="register-nickname">昵称</label>
        <input id="register-nickname" v-model="nickname" required maxlength="255" autocomplete="nickname" :disabled="busy" />
        <label for="register-password">密码 <small>6–64 个字符，UTF-8 ≤ 72 字节</small></label>
        <input id="register-password" v-model="password" type="password" required minlength="6" maxlength="64" autocomplete="new-password" :disabled="busy" />
        <label for="register-confirmation">确认密码</label>
        <input id="register-confirmation" v-model="confirmation" type="password" required maxlength="64" autocomplete="new-password" :disabled="busy" />
        <p v-if="error" class="error-message" role="alert">{{ error }}</p>
        <button type="submit" class="primary-button auth-submit" :disabled="busy">{{ busy ? '正在注册…' : '注册账号' }}<span aria-hidden="true">→</span></button>
      </form>
      <div class="development-note"><strong>注册 ≠ 校园认证</strong><p>注册后请登录并提交校园核验申请。管理员人工审核通过且资格有效后，才可进入失物招领业务。</p></div>
    </div>
  </section>
</template>
