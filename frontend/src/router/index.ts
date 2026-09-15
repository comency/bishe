import { createRouter, createWebHistory, type RouterHistory } from 'vue-router'
import type { Pinia } from 'pinia'
import { useAuthStore } from '../stores/auth'

// Only existing local business routes can be used as a login return destination.
export function safeReturnTo(value: unknown): string {
  if (typeof value !== 'string' || /[\\\r\n]/.test(value)) return '/items'
  return /^\/items(?:\?[^#]*)?$/.test(value) ? value : '/items'
}

export function createAppRouter(pinia: Pinia, history: RouterHistory = createWebHistory()) {
  const router = createRouter({
    history,
    routes: [
      { path: '/', redirect: '/items' },
      { path: '/login', name: 'login', component: () => import('../views/LoginView.vue'), meta: { title: '登录', guest: true } },
      { path: '/register', name: 'register', component: () => import('../views/RegisterView.vue'), meta: { title: '注册', guest: true } },
      { path: '/items', name: 'items', component: () => import('../views/ItemsView.vue'), meta: { title: '物品大厅', requiresAuth: true } },
      { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('../views/NotFoundView.vue'), meta: { title: '页面未找到' } },
    ],
    scrollBehavior: () => ({ top: 0 }),
  })
  router.beforeEach(to => {
    const auth = useAuthStore(pinia)
    // Presence of a local token is only a UI hint. The API is the authorization authority.
    if (to.meta.requiresAuth && !auth.hasSession) return { name: 'login', query: { returnTo: safeReturnTo(to.fullPath) } }
    if (to.meta.guest && auth.hasSession) return safeReturnTo(to.query.returnTo)
  })
  router.afterEach(to => {
    if (typeof document !== 'undefined') document.title = `${String(to.meta.title ?? '校园服务')} · 校园拾光`
  })
  return router
}
