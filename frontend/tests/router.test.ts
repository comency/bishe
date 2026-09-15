import { describe, expect, it } from 'vitest'
import { createPinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import { createAppRouter, safeReturnTo } from '../src/router'
import { useAuthStore } from '../src/stores/auth'

describe('safe navigation and route guards', () => {
  it.each(['https://example.com', '//example.com', '/\\example.com', '/login', '/register', '/admin/items', '/items/42', '/items#unsafe', '/items\n'])('rejects unapproved return destination %s', input => {
    expect(safeReturnTo(input)).toBe('/items')
  })

  it('allows only the current local hall route including its query', () => {
    expect(safeReturnTo('/items?keyword=book')).toBe('/items?keyword=book')
    expect(safeReturnTo(['/items'])).toBe('/items')
  })

  it('redirects unauthenticated navigation to login and retains a safe destination', async () => {
    const router = createAppRouter(createPinia(), createMemoryHistory())
    await router.push('/items?keyword=book')
    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.returnTo).toBe('/items?keyword=book')
  })

  it('allows login/register without any session', async () => {
    const router = createAppRouter(createPinia(), createMemoryHistory())
    await router.push('/register')
    expect(router.currentRoute.value.name).toBe('register')
    await router.push('/login')
    expect(router.currentRoute.value.name).toBe('login')
  })

  it('uses token presence only to navigate, not to invent campus verification', async () => {
    const pinia = createPinia()
    const auth = useAuthStore(pinia)
    auth.signIn({ token: 'unit-test-token-00001', userId: 1, username: 'synthetic', nickname: '合成测试', role: 'USER' })
    const router = createAppRouter(pinia, createMemoryHistory())
    await router.push('/login?returnTo=https://example.com')
    expect(router.currentRoute.value.name).toBe('items')
    expect(auth.session).not.toHaveProperty('verified')
    auth.clear()
    await router.push('/items?next=1')
    expect(router.currentRoute.value.name).toBe('login')
  })

  it('renders the 404 route instead of pretending future features exist', async () => {
    const router = createAppRouter(createPinia(), createMemoryHistory())
    await router.push('/verification')
    expect(router.currentRoute.value.name).toBe('not-found')
  })
})
