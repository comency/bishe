export const verificationLabels = {
  UNVERIFIED: '未认证', PENDING: '等待人工审核', VERIFIED: '认证有效',
  REJECTED: '申请已驳回', EXPIRED: '认证已到期', REVOKED: '资格已撤销',
} as const

export type VerificationStatus = keyof typeof verificationLabels
export interface VerificationSummary {
  userId: number
  campusId: string | null
  status: VerificationStatus
  version: number
  expiresAt: string | null
  validThrough: string | null
  isTest: boolean
  reason: string | null
}
export interface UserMe {
  userId: number
  username: string
  nickname: string
  contact: string | null
  role: 'USER' | 'ADMIN'
  version: number
  verification: VerificationSummary
}
export interface PublicConfig {
  campusName: string | null
  timezone: string
  isTest: boolean
  verificationInstructions: string
  supportContact: string | null
  categories: string[]
  maxImageBytes: number
  maxImagesPerItem: number
  aiEnabled: boolean
}
export interface Page<T> { records: T[]; total: number; page: number; pageSize: number }
export interface VerificationApplication {
  id: number
  applicationVersion: number
  status: 'PENDING' | 'VERIFIED' | 'REJECTED'
  realName: string
  studentNumber: string | null
  statement: string | null
  submittedAt: string
  reviewedAt: string | null
  validThrough: string | null
  reason: string | null
}
export interface AdminVerificationApplication extends VerificationApplication {
  method: 'IN_PERSON' | 'ROSTER' | null
  evidenceSummary: string | null
  internalNote: string | null
  reviewerId: number | null
}
export interface AdminVerificationSummary extends VerificationSummary {
  username: string
  realName: string | null
  currentApplicationId: number | null
  applicationVersion: number
}
export interface MyVerification {
  summary: VerificationSummary
  currentApplication: VerificationApplication | null
  history: Page<VerificationApplication>
}
export interface AdminVerification {
  summary: AdminVerificationSummary
  currentApplication: AdminVerificationApplication | null
  history: Page<AdminVerificationApplication>
}

export function effectiveStatus(summary: VerificationSummary, now = Date.now()): VerificationStatus {
  if (summary.status !== 'VERIFIED') return summary.status
  const expiry = summary.expiresAt ? Date.parse(summary.expiresAt) : NaN
  return Number.isFinite(expiry) && now < expiry ? 'VERIFIED' : 'EXPIRED'
}

export function canSubmitVerification(status: VerificationStatus): boolean {
  return ['UNVERIFIED', 'REJECTED', 'EXPIRED'].includes(status)
}

export function assertUserMe(value: unknown): asserts value is UserMe {
  if (!value || typeof value !== 'object') throw new Error('本人资料响应格式不正确。')
  const user = value as UserMe
  if (!Number.isSafeInteger(user.userId) || user.userId < 1 ||
    !Number.isSafeInteger(user.version) || user.version < 0 ||
    !['USER', 'ADMIN'].includes(user.role) || typeof user.username !== 'string' ||
    typeof user.nickname !== 'string' || !user.verification ||
    user.verification.userId !== user.userId ||
    !Object.hasOwn(verificationLabels, user.verification.status) ||
    !Number.isSafeInteger(user.verification.version) || user.verification.version < 0) {
    throw new Error('本人资料响应格式不正确。')
  }
}

export function formatTime(value: string | null, timezone = 'Asia/Shanghai'): string {
  if (!value) return '—'
  try { return new Intl.DateTimeFormat('zh-CN', { timeZone: timezone, dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) }
  catch { return value }
}

export function campusToday(timezone = 'Asia/Shanghai'): string {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: timezone, year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date())
  return ['year', 'month', 'day'].map(type => parts.find(part => part.type === type)?.value).join('-')
}
