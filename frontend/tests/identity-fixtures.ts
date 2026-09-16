import type { UserMe, VerificationStatus } from '../src/lib/identity'

export const session = { token: 'unit-test-token-00001', userId: 1, username: 'synthetic', nickname: '合成测试', role: 'USER' as const }
export function profile(status: VerificationStatus = 'UNVERIFIED', role: 'USER' | 'ADMIN' = 'USER'): UserMe {
  return { userId: 1, username: 'synthetic', nickname: '合成测试', role, contact: null, version: 0,
    verification: { userId: 1, campusId: 'TEST_CAMPUS', status, version: 0, expiresAt: status === 'VERIFIED' ? '2099-10-01T00:00:00+08:00' : null,
      validThrough: status === 'VERIFIED' ? '2099-09-30' : null, isTest: true, reason: null } }
}
export function envelope(data: unknown, status = 200, errorCode?: string): Response {
  return new Response(JSON.stringify({ code: status === 200 ? 0 : -1, message: status === 200 ? 'success' : '测试错误', data, errorCode }), {
    status, headers: { 'Content-Type': 'application/json' },
  })
}
