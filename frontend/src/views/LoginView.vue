<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { request } from '../api'
import { useAuthStore } from '../stores/auth'
import { safeReturnTo } from '../router'
import PublicInstructions from '../components/PublicInstructions.vue'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const username = ref('')
const password = ref('')
const busy = ref(false)
const error = ref('')
const controller = new AbortController()
onBeforeUnmount(() => { controller.abort(); password.value = '' })

async function submit() {
  if (busy.value) return
  error.value = ''
  if (!username.value.trim() || !password.value) { error.value = '请输入用户名和密码。'; return }
  busy.value = true
  try {
    const data = await request<unknown>('/api/auth/login', {
      method: 'POST', auth: false, signal: controller.signal,
      body: { username: username.value.trim(), password: password.value },
    })
    if (controller.signal.aborted) return
    auth.signIn(data)
    password.value = ''
    // Cached role only selects a destination; the router always loads /users/me before entry.
    await router.replace(auth.session?.role === 'ADMIN' ? '/admin/verifications' : safeReturnTo(route.query.returnTo))
  } catch (failure) {
    if (!controller.signal.aborted) error.value = failure instanceof Error ? failure.message : '登录失败，请稍后重试。'
  } finally { busy.value = false }
}
</script>

<template>
  <section class="auth-page">
    <div class="welcome-panel">
      <span class="eyebrow">找回物品，也找回安心</span>
      <h1>你在寻找的，<br />或许也在等你。</h1>
      <p>让一次失而复得，成为校园里温暖的小事。<br />从这里开始，连接每一份拾金不昧的善意。</p>
      <div class="welcome-art" aria-hidden="true">
        <span class="art-orbit orbit-one"></span><span class="art-orbit orbit-two"></span>
        <svg viewBox="0 0 280 185" class="bag-art"><path d="M76 58h129l-9 99H84z" fill="#f4e6c9" stroke="#174f3e" stroke-width="3"/><path d="M111 65V44a30 30 0 0160 0v21" fill="none" stroke="#174f3e" stroke-width="4" stroke-linecap="round"/><path d="M84 157h112" stroke="#174f3e" stroke-width="3"/><rect x="154" y="83" width="49" height="35" rx="5" fill="#fffef8" stroke="#174f3e" stroke-width="2" transform="rotate(-10 178 100)"/><path d="m171 99 7 7 12-16" fill="none" stroke="#174f3e" stroke-width="3" stroke-linecap="round"/><circle cx="65" cy="104" r="7" fill="#cf9f59"/><path d="M225 49v18m-9-9h18" stroke="#769783" stroke-width="3"/></svg>
        <span class="art-caption">每一件小物，都有被珍惜的故事</span>
      </div>
      <div class="welcome-chips"><span>校园互助</span><span>失物招领一期</span><span>零交易</span></div>
    </div>
    <div class="auth-card">
      <span class="tiny-label">欢迎回来</span>
      <h2>登录校园拾光</h2>
      <p class="muted">登录并完成在校身份人工核验后，使用校园互助服务。</p>
      <p v-if="auth.notice" class="notice" role="status">{{ auth.notice }}</p>
      <p v-if="route.query.registered === '1'" class="notice" role="status">注册成功，请使用新账号登录。注册不代表在校身份验证通过。</p>
      <form class="auth-form" @submit.prevent="submit">
        <label for="login-username">用户名</label>
        <input id="login-username" v-model="username" autocomplete="username" required maxlength="40" placeholder="请输入用户名" :disabled="busy" />
        <label for="login-password">密码</label>
        <input id="login-password" v-model="password" type="password" autocomplete="current-password" required maxlength="64" placeholder="请输入密码" :disabled="busy" />
        <p v-if="error" class="error-message" role="alert">{{ error }}</p>
        <button class="primary-button auth-submit" type="submit" :disabled="busy">{{ busy ? '正在登录…' : '登录' }}<span aria-hidden="true">→</span></button>
      </form>
      <p class="auth-switch">还没有账号？ <RouterLink :to="{ name: 'register', query: { returnTo: safeReturnTo(route.query.returnTo) } }">注册账号</RouterLink></p>
      <PublicInstructions />
    </div>
  </section>
</template>
