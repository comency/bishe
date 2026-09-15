export interface Session {
  token: string
  userId: number
  username: string
  nickname: string
  role: 'USER' | 'ADMIN'
}

export interface SessionStorage {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
  removeItem(key: string): void
}

export const SESSION_KEY = 'campus-lost-found.session.v1'

export function parseSession(value: unknown): Session | null {
  if (!value || typeof value !== 'object') return null
  const candidate = value as Record<string, unknown>
  if (
    typeof candidate.token !== 'string' || !/^[a-zA-Z0-9_-]{16,256}$/.test(candidate.token) ||
    typeof candidate.userId !== 'number' || !Number.isSafeInteger(candidate.userId) || candidate.userId < 1 ||
    typeof candidate.username !== 'string' || !candidate.username.trim() ||
    typeof candidate.nickname !== 'string' || !candidate.nickname.trim() ||
    (candidate.role !== 'USER' && candidate.role !== 'ADMIN')
  ) return null
  return {
    token: candidate.token, userId: candidate.userId, username: candidate.username,
    nickname: candidate.nickname, role: candidate.role,
  }
}

export function browserSessionStorage(): SessionStorage | undefined {
  try { return typeof window === 'undefined' ? undefined : window.sessionStorage }
  catch { return undefined }
}

export function restoreSession(storage?: SessionStorage): Session | null {
  try {
    const raw = storage?.getItem(SESSION_KEY)
    if (!raw) return null
    const session = parseSession(JSON.parse(raw))
    if (!session) storage?.removeItem(SESSION_KEY)
    return session
  } catch {
    persistSession(null, storage)
    return null
  }
}

export function persistSession(session: Session | null, storage?: SessionStorage): void {
  try {
    if (session) storage?.setItem(SESSION_KEY, JSON.stringify(session))
    else storage?.removeItem(SESSION_KEY)
  } catch {
    // Private-mode/storage failures must not block in-memory sign-in or sign-out.
  }
}
