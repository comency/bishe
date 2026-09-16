<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { request } from './api'
import { useAuthStore } from './stores/auth'
import { safeReturnTo } from './router'
import { useConfigStore } from './stores/config'

const auth = useAuthStore()
const settings = useConfigStore()
const router = useRouter()
const route = useRoute()
const signingOut = ref(false)
const initials = computed(() => auth.session?.nickname.slice(0, 1) ?? '拾')
let expiryTimer: ReturnType<typeof setInterval> | undefined
onMounted(() => { void settings.load(); expiryTimer = setInterval(auth.checkExpiry, 1000) })
onBeforeUnmount(() => clearInterval(expiryTimer))

watch(() => auth.canUseBusiness, allowed => {
  if (!allowed && auth.hasSession && route.meta.requiresVerification) void router.replace('/verification')
})
watch(() => auth.revision, () => {
  if (auth.hasSession && route.meta.requiresVerification && !auth.canUseBusiness) void router.replace('/verification')
})
watch(() => auth.isAdmin, allowed => {
  if (!allowed && auth.hasSession && route.meta.requiresAdmin) void router.replace('/verification')
})

watch(() => auth.session?.token, (token, previous) => {
  if (!token && previous && route.meta.requiresAuth) {
    void router.replace({ name: 'login', query: { returnTo: safeReturnTo(route.fullPath) } })
  }
})

async function signOut() {
  if (signingOut.value) return
  signingOut.value = true
  const token = auth.session?.token
  let message = '已退出登录。'
  try { await request('/api/auth/logout', { method: 'POST' }) }
  catch { message = '本机登录信息已清除；服务端注销未确认，请勿继续使用旧凭证。' }
  finally {
    if (auth.session?.token === token) auth.clear(message)
    signingOut.value = false
  }
}
</script>

<template>
  <a class="skip-link" href="#main-content">跳到主要内容</a>
  <div class="app-shell">
    <aside class="sidebar" aria-label="主导航">
      <RouterLink to="/items" class="brand" aria-label="校园拾光首页">
        <span class="brand-icon" aria-hidden="true">拾</span>
        <span><strong>校园拾光</strong><small>LOST &amp; FOUND</small></span>
      </RouterLink>
      <p class="nav-caption">校园里的每一份善意</p>
      <nav>
        <RouterLink v-if="auth.canUseBusiness" to="/items" class="nav-link"><span aria-hidden="true">▦</span> 物品大厅</RouterLink>
        <RouterLink v-if="auth.canUseBusiness" to="/items/mine" class="nav-link">我的发布</RouterLink>
        <RouterLink v-if="auth.canUseBusiness" to="/claims/mine" class="nav-link">我的认领</RouterLink>
        <RouterLink v-if="auth.canUseBusiness" to="/claims/incoming" class="nav-link">收到的认领</RouterLink>
        <RouterLink v-if="auth.canUseBusiness" to="/assistant" class="nav-link">智能使用助手</RouterLink>
        <template v-if="auth.hasSession">
          <RouterLink to="/verification" class="nav-link"><span aria-hidden="true">✓</span> 校园认证</RouterLink>
          <RouterLink to="/profile" class="nav-link"><span aria-hidden="true">◇</span> 本人资料</RouterLink>
          <RouterLink v-if="auth.isAdmin" to="/admin/verifications" class="nav-link"><span aria-hidden="true">▤</span> 人工认证审核</RouterLink>
          <RouterLink v-if="auth.isAdmin" to="/admin/items" class="nav-link">物品内容审核</RouterLink>
          <RouterLink v-if="auth.isAdmin" to="/admin/claims" class="nav-link">认领管理</RouterLink>
          <RouterLink v-if="auth.isAdmin" to="/admin/logs" class="nav-link">业务操作日志</RouterLink>
        </template>
        <template v-else>
          <RouterLink to="/login" class="nav-link">登录</RouterLink>
          <RouterLink to="/register" class="nav-link">注册账号</RouterLink>
        </template>
      </nav>
      <div class="sidebar-note">
        <span class="tiny-label">一期 · 人工校园核验</span>
        <p>让遗失的物品，<br />找到回去的路。</p>
        <small>当前仅用于本地开发联调，<br />请勿输入真实校园个人信息。</small>
      </div>
    </aside>

    <div class="workspace">
      <header class="topbar">
        <span class="breadcrumb">校园服务 <span aria-hidden="true">/</span> <strong>{{ route.meta.title }}</strong></span>
        <div v-if="auth.session" class="account">
          <span class="avatar" aria-hidden="true">{{ initials }}</span>
          <span class="account-name">{{ auth.session.nickname }}</span>
          <button class="text-button" :disabled="signingOut" @click="signOut">{{ signingOut ? '退出中…' : '退出' }}</button>
        </div>
        <span v-else class="environment-badge"><i aria-hidden="true"></i> 本地开发环境</span>
      </header>
      <main id="main-content" class="main-content">
        <p v-if="settings.config?.isTest" class="environment-notice" role="note">测试校园 · 请使用合成信息。测试核验不代表真实在校身份。</p>
        <!-- A different resource path must unmount the old request/form state, even during a write. -->
        <RouterView v-if="(!route.meta.requiresAuth || auth.hasSession) && (!route.meta.requiresVerification || auth.canUseBusiness) && (!route.meta.requiresAdmin || auth.isAdmin)" :key="route.meta.guest ? 'guest' : `${auth.session?.token ?? 'guest'}:${route.path}`" />
      </main>
      <footer>校园失物招领智能管理系统 <span>人工核验准入 · 校园互助</span></footer>
    </div>
  </div>
</template>
