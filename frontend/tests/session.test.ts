import { afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { parseSession, persistSession, restoreSession, SESSION_KEY, type SessionStorage } from '../src/lib/session'
import { useAuthStore } from '../src/stores/auth'

const sample = { token: 'unit-test-token-00001', userId: 1, username: 'synthetic', nickname: '合成测试', role: 'USER' }

function memoryStorage(): SessionStorage {
  const entries = new Map<string, string>()
  return {
    getItem: key => entries.get(key) ?? null,
    setItem: (key, value) => { entries.set(key, value) },
    removeItem: key => { entries.delete(key) },
  }
}

afterEach(() => vi.unstubAllGlobals())

describe('session storage', () => {
  it('keeps only the allowed login fields, never a password or invented verification', () => {
    expect(parseSession({ ...sample, password: 'discard-me', verified: true })).toEqual(sample)
  })

  it.each([null, {}, { ...sample, token: '' }, { ...sample, token: 'header\ninjection' },
    { ...sample, role: 'SUPERUSER' }, { ...sample, userId: -1 }, { ...sample, nickname: null }])('rejects invalid data: %j', value => {
    expect(parseSession(value)).toBeNull()
  })

  it('restores a well-formed session from only the namespaced session key', () => {
    const storage = memoryStorage()
    const parsed = parseSession(sample)
    persistSession(parsed, storage)
    expect(restoreSession(storage)).toEqual(parsed)
    persistSession(null, storage)
    expect(storage.getItem(SESSION_KEY)).toBeNull()
  })

  it('removes corrupt or old-format data', () => {
    const storage = memoryStorage()
    storage.setItem(SESSION_KEY, '{broken')
    expect(restoreSession(storage)).toBeNull()
    expect(storage.getItem(SESSION_KEY)).toBeNull()
    storage.setItem(SESSION_KEY, JSON.stringify({ token: 'invalid' }))
    expect(restoreSession(storage)).toBeNull()
    expect(storage.getItem(SESSION_KEY)).toBeNull()
  })

  it('degrades safely when browser storage is inaccessible', () => {
    const unavailable = () => { throw new Error('unavailable') }
    const storage = { getItem: unavailable, setItem: unavailable, removeItem: unavailable }
    expect(restoreSession(storage)).toBeNull()
    expect(() => persistSession(null, storage)).not.toThrow()
    expect(() => persistSession(parseSession(sample), storage)).not.toThrow()
  })

  it('clears both the Pinia session and sessionStorage on logout or 401', () => {
    const storage = memoryStorage()
    vi.stubGlobal('window', { sessionStorage: storage })
    setActivePinia(createPinia())
    const auth = useAuthStore()
    auth.signIn(sample)
    expect(auth.hasSession).toBe(true)
    expect(storage.getItem(SESSION_KEY)).not.toBeNull()
    auth.clear('会话失效')
    expect(auth.session).toBeNull()
    expect(auth.hasSession).toBe(false)
    expect(auth.notice).toBe('会话失效')
    expect(storage.getItem(SESSION_KEY)).toBeNull()
  })
})
