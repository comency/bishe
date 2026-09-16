import { afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia } from 'pinia'
import { canSubmitVerification, effectiveStatus } from '../src/lib/identity'
import { useAuthStore } from '../src/stores/auth'
import { profile, session, envelope } from './identity-fixtures'

afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals() })

describe('current campus eligibility', () => {
  it('expires exactly at the exclusive expiresAt instant and preserves historical approval', () => {
    const summary = profile('VERIFIED').verification
    const deadline = Date.parse(summary.expiresAt!)
    expect(effectiveStatus(summary, deadline - 1)).toBe('VERIFIED')
    expect(effectiveStatus(summary, deadline)).toBe('EXPIRED')
    expect(effectiveStatus(summary, deadline + 1)).toBe('EXPIRED')
    expect(summary.status).toBe('VERIFIED')
  })

  it('does not grant access for missing or invalid expiry dates', () => {
    expect(effectiveStatus({ ...profile('VERIFIED').verification, expiresAt: null })).toBe('EXPIRED')
    expect(effectiveStatus({ ...profile('VERIFIED').verification, expiresAt: 'invalid' })).toBe('EXPIRED')
  })

  it.each([
    ['UNVERIFIED', true], ['PENDING', false], ['VERIFIED', false], ['REJECTED', true], ['EXPIRED', true], ['REVOKED', false],
  ] as const)('allows new submissions for %s: %s', (status, allowed) => {
    expect(canSubmitVerification(status)).toBe(allowed)
  })

  it('clears business eligibility at expiry while retaining the authenticated identity', () => {
    vi.useFakeTimers()
    const deadline = Date.parse(profile('VERIFIED').verification.expiresAt!)
    vi.setSystemTime(deadline - 1)
    const auth = useAuthStore(createPinia())
    auth.signIn(session)
    auth.applyMe(profile('VERIFIED'))
    const revision = auth.revision
    expect(auth.canUseBusiness).toBe(true)
    vi.setSystemTime(deadline)
    auth.checkExpiry()
    expect(auth.canUseBusiness).toBe(false)
    expect(auth.hasSession).toBe(true)
    expect(auth.revision).toBe(revision + 1)
  })

  it('invalidates eligibility, but not the token, on a verification-required response', async () => {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockImplementation(async () => envelope(null, 403, 'VERIFICATION_REQUIRED')))
    const auth = useAuthStore(createPinia())
    auth.signIn(session)
    auth.applyMe(profile('VERIFIED'))
    await expect(auth.client('/api/items')).rejects.toMatchObject({ errorCode: 'VERIFICATION_REQUIRED' })
    expect(auth.hasSession).toBe(true)
    expect(auth.me).toBeNull()
    expect(auth.canUseBusiness).toBe(false)
  })

  it('does not restore stale eligibility when an earlier refresh arrives last', async () => {
    let finishEarlier: ((response: Response) => void) | undefined
    const fetcher = vi.fn<typeof fetch>().mockImplementationOnce(() => new Promise<Response>(resolve => { finishEarlier = resolve }))
      .mockImplementationOnce(async () => envelope(profile('REVOKED')))
    vi.stubGlobal('fetch', fetcher)
    const auth = useAuthStore(createPinia())
    auth.signIn(session)
    const earlier = auth.refreshMe()
    await auth.refreshMe()
    finishEarlier!(envelope(profile('VERIFIED')))
    await expect(earlier).rejects.toThrow('较早的资格查询已作废')
    expect(auth.me?.verification.status).toBe('REVOKED')
    expect(auth.canUseBusiness).toBe(false)
  })

  it('rejects an inconsistent identity response instead of displaying another user', () => {
    const auth = useAuthStore(createPinia())
    auth.signIn(session)
    const mismatched = { ...profile(), userId: 2, verification: { ...profile().verification, userId: 2 } }
    expect(() => auth.applyMe(mismatched)).toThrow('会话与本人资料不一致')
    expect(auth.me).toBeNull()
  })

  it('does not overwrite a newly submitted verification with an earlier eligibility read', async () => {
    let finishEarlier: ((response: Response) => void) | undefined
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockImplementation(() => new Promise<Response>(resolve => { finishEarlier = resolve })))
    const auth = useAuthStore(createPinia())
    auth.signIn(session)
    auth.applyMe(profile('UNVERIFIED'))
    const earlier = auth.refreshMe()
    auth.applyVerification({ ...profile('PENDING').verification, version: 1 })
    finishEarlier!(envelope(profile('UNVERIFIED')))
    await expect(earlier).rejects.toThrow('较早的资格查询已作废')
    expect(auth.me?.verification.status).toBe('PENDING')
    expect(auth.me?.verification.version).toBe(1)
  })
})
