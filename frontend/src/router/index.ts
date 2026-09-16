import { createRouter, createWebHistory, type RouterHistory } from 'vue-router'
import type { Pinia } from 'pinia'
import { useAuthStore } from '../stores/auth'

// Only implemented, same-origin routes can be login return destinations.
export function safeReturnTo(value: unknown): string {
  if (typeof value !== 'string' || /[\\\r\n]/.test(value)) return '/items'
  return /^\/(?:items(?:\/(?:new|mine|\d+(?:\/edit)?))?|claims\/(?:mine|incoming|\d+)|assistant|profile|verification|admin\/(?:(?:verifications|items|claims)(?:\/\d+)?|logs))(?:\?[^#]*)?$/.test(value) ? value : '/items'
}

export function createAppRouter(pinia: Pinia, history: RouterHistory = createWebHistory()) {
  const router = createRouter({
    history,
    routes: [
      { path: '/', redirect: '/items' },
      { path: '/assistant', component: () => import('../views/AssistantView.vue'), meta: { title: '智能使用助手', requiresAuth: true, requiresVerification: true } },
      { path: '/claims/mine', component: () => import('../views/ClaimsView.vue'), meta: { title: '我的认领', requiresAuth: true, requiresVerification: true } },
      { path: '/claims/incoming', component: () => import('../views/ClaimsView.vue'), meta: { title: '收到的认领', requiresAuth: true, requiresVerification: true } },
      { path: '/claims/:id(\\d+)', component: () => import('../views/ClaimDetailView.vue'), meta: { title: '认领详情', requiresAuth: true, requiresVerification: true } },
      { path: '/admin/claims', component: () => import('../views/ClaimsView.vue'), meta: { title: '认领管理', requiresAuth: true, requiresAdmin: true } },
      { path: '/admin/claims/:id(\\d+)', component: () => import('../views/ClaimDetailView.vue'), meta: { title: '认领管理详情', requiresAuth: true, requiresAdmin: true } },
      { path: '/admin/logs', component: () => import('../views/AdminLogsView.vue'), meta: { title: '业务操作日志', requiresAuth: true, requiresAdmin: true } },
      { path: '/login', name: 'login', component: () => import('../views/LoginView.vue'), meta: { title: '登录', guest: true } },
      { path: '/register', name: 'register', component: () => import('../views/RegisterView.vue'), meta: { title: '注册', guest: true } },
      { path: '/items', name: 'items', component: () => import('../views/ItemsView.vue'), meta: { title: '物品大厅', requiresAuth: true, requiresVerification: true } },
      { path: '/items/mine', component: () => import('../views/ItemsView.vue'), meta: { title: '我的发布', requiresAuth: true, requiresVerification: true } },
      { path: '/items/new', component: () => import('../views/ItemFormView.vue'), meta: { title: '发布启事', requiresAuth: true, requiresVerification: true } },
      { path: '/items/:id(\\d+)/edit', component: () => import('../views/ItemFormView.vue'), meta: { title: '编辑启事', requiresAuth: true, requiresVerification: true } },
      { path: '/items/:id(\\d+)', component: () => import('../views/ItemDetailView.vue'), meta: { title: '物品详情', requiresAuth: true, requiresVerification: true } },
      { path: '/admin/items', component: () => import('../views/ItemsView.vue'), meta: { title: '物品内容审核', requiresAuth: true, requiresAdmin: true } },
      { path: '/admin/items/:id(\\d+)', component: () => import('../views/ItemDetailView.vue'), meta: { title: '物品审核详情', requiresAuth: true, requiresAdmin: true } },
      { path: '/verification', name: 'verification', component: () => import('../views/VerificationView.vue'), meta: { title: '校园身份认证', requiresAuth: true } },
      { path: '/profile', name: 'profile', component: () => import('../views/ProfileView.vue'), meta: { title: '本人资料', requiresAuth: true } },
      { path: '/admin/verifications', name: 'admin-verifications', component: () => import('../views/AdminVerificationsView.vue'), meta: { title: '人工认证审核', requiresAuth: true, requiresAdmin: true } },
      { path: '/admin/verifications/:userId(\\d+)', name: 'admin-verification', component: () => import('../views/AdminVerificationView.vue'), meta: { title: '认证核对详情', requiresAuth: true, requiresAdmin: true } },
      { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('../views/NotFoundView.vue'), meta: { title: '页面未找到' } },
    ],
    scrollBehavior: () => ({ top: 0 }),
  })
  router.beforeEach(async to => {
    const auth = useAuthStore(pinia)
    if (to.meta.requiresAuth && !auth.hasSession) return { name: 'login', query: { returnTo: safeReturnTo(to.fullPath) } }
    if (auth.hasSession && (to.meta.requiresAuth || to.meta.guest)) {
      try { await auth.refreshMe() }
      catch (failure) {
        if (!auth.hasSession) return to.meta.guest ? true : { name: 'login', query: { returnTo: safeReturnTo(to.fullPath) } }
        auth.notice = failure instanceof Error ? failure.message : '暂时无法核实当前资格，请重试。'
        if (to.meta.requiresVerification || to.meta.requiresAdmin || to.meta.guest) {
          auth.requireVerification(auth.notice)
          return { name: 'verification' }
        }
        return true
      }
      if (to.meta.requiresAdmin && !auth.isAdmin) { auth.notice = '当前账号无管理权限。'; return { name: 'verification' } }
      if (to.meta.requiresVerification && !auth.canUseBusiness) return { name: 'verification' }
      if (to.meta.guest) return auth.isAdmin ? '/admin/verifications' : auth.canUseBusiness ? safeReturnTo(to.query.returnTo) : '/verification'
    }
  })
  router.afterEach(to => {
    if (typeof document !== 'undefined') document.title = `${String(to.meta.title ?? '校园服务')} · 校园拾光`
  })
  return router
}
