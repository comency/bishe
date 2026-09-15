<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { request } from './api'
import { useAuthStore } from './stores/auth'
import { safeReturnTo } from './router'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const signingOut = ref(false)
const initials = computed(() => auth.session?.nickname.slice(0, 1) ?? '拾')

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
        <RouterLink to="/items" class="nav-link"><span aria-hidden="true">▦</span> 物品大厅</RouterLink>
        <span class="nav-disabled"><span aria-hidden="true">＋</span> 发布信息 <small>待开发</small></span>
        <span class="nav-disabled"><span aria-hidden="true">◇</span> 我的认领 <small>待开发</small></span>
        <span class="nav-disabled"><span aria-hidden="true">✓</span> 校园认证 <small>待开发</small></span>
      </nav>
      <div class="sidebar-note">
        <span class="tiny-label">一期 · 开发基线</span>
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
        <RouterView :key="auth.session?.token ?? 'guest'" />
      </main>
      <footer>校园失物招领智能管理系统 <span>一期建设中 · 人工身份审核尚未接入</span></footer>
    </div>
  </div>
</template>
