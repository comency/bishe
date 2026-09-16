import { afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import { createAppRouter, safeReturnTo } from '../src/router'
import { useAuthStore } from '../src/stores/auth'
import { session, profile, envelope } from './identity-fixtures'

afterEach(() => vi.unstubAllGlobals())

describe('safe navigation and route guards', () => {
  it.each(['https://example.com', '//example.com', '/\\example.com', '/login', '/register', '/admin/unknown', '/items/42/delete', '/items#unsafe', '/items\n'])('rejects unapproved return destination %s', input => {
    expect(safeReturnTo(input)).toBe('/items')
  })

  it('allows implemented local routes including their query', () => {
    expect(safeReturnTo('/items?keyword=book')).toBe('/items?keyword=book')
    expect(safeReturnTo(['/items'])).toBe('/items')
    expect(safeReturnTo('/profile')).toBe('/profile')
    expect(safeReturnTo('/verification')).toBe('/verification')
    expect(safeReturnTo('/admin/verifications/3')).toBe('/admin/verifications/3')
    for (const path of ['/items/new', '/items/mine', '/items/42', '/items/42/edit', '/admin/items', '/admin/items/42']) expect(safeReturnTo(path)).toBe(path)
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

  it('requires a fresh /users/me result, never cached token or role, to enter business', async () => {
    const pinia = createPinia()
    const auth = useAuthStore(pinia)
    auth.signIn(session)
    const fetcher = vi.fn<typeof fetch>().mockImplementation(async () => envelope(profile('VERIFIED')))
    vi.stubGlobal('fetch', fetcher)
    const router = createAppRouter(pinia, createMemoryHistory())
    await router.push('/login?returnTo=https://example.com')
    expect(router.currentRoute.value.name).toBe('items')
    expect(fetcher).toHaveBeenCalledWith('/api/users/me', expect.anything())
    expect(auth.session).not.toHaveProperty('verified')
    auth.clear()
    await router.push('/items?next=1')
    expect(router.currentRoute.value.name).toBe('login')
  })

  it.each(['UNVERIFIED', 'PENDING', 'REJECTED', 'EXPIRED', 'REVOKED'] as const)('redirects %s users to their certification result', async status => {
    const pinia = createPinia()
    const auth = useAuthStore(pinia)
    auth.signIn(session)
    auth.applyMe(profile('VERIFIED'))
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockImplementation(async () => envelope(profile(status))))
    const router = createAppRouter(pinia, createMemoryHistory())
    await router.push('/items')
    expect(router.currentRoute.value.name).toBe('verification')
    expect(auth.canUseBusiness).toBe(false)
  })

  it('allows unverified administrators into management but not ordinary business', async () => {
    const pinia = createPinia()
    const auth = useAuthStore(pinia)
    auth.signIn({ ...session, role: 'ADMIN' })
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockImplementation(async () => envelope(profile('UNVERIFIED', 'ADMIN'))))
    const router = createAppRouter(pinia, createMemoryHistory())
    await router.push('/admin/verifications')
    expect(router.currentRoute.value.name).toBe('admin-verifications')
    await router.push('/items')
    expect(router.currentRoute.value.name).toBe('verification')
  })

  it('rejects a cached administrator role when current server role is USER', async () => {
    const pinia = createPinia()
    useAuthStore(pinia).signIn({ ...session, role: 'ADMIN' })
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockImplementation(async () => envelope(profile('VERIFIED'))))
    const router = createAppRouter(pinia, createMemoryHistory())
    await router.push('/admin/verifications/2')
    expect(router.currentRoute.value.name).toBe('verification')
  })

  it('fails closed when the latest eligibility cannot be checked', async () => {
    const pinia = createPinia()
    const auth = useAuthStore(pinia)
    auth.signIn(session)
    auth.applyMe(profile('VERIFIED'))
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockRejectedValue(new Error('offline')))
    const router = createAppRouter(pinia, createMemoryHistory())
    await router.push('/items')
    expect(router.currentRoute.value.name).toBe('verification')
    expect(auth.canUseBusiness).toBe(false)
  })

  it('clears expired sessions and returns to login without redirect loops', async () => {
    const pinia = createPinia()
    const auth = useAuthStore(pinia)
    auth.signIn(session)
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockImplementation(async () => envelope(null, 401)))
    const router = createAppRouter(pinia, createMemoryHistory())
    await router.push('/items')
    expect(router.currentRoute.value.name).toBe('login')
    expect(auth.hasSession).toBe(false)
  })

  it('requires login for identity pages while future routes remain 404', async () => {
    const router = createAppRouter(createPinia(), createMemoryHistory())
    await router.push('/verification')
    expect(router.currentRoute.value.name).toBe('login')
    await router.push('/claims')
    expect(router.currentRoute.value.name).toBe('not-found')
  })
})
