import type { Page } from './identity'
export const claimStatuses = { APPLIED: '待处理', ACCEPTED: '交接中', REJECTED: '已拒绝', CANCELLED: '已取消', COMPLETED: '已归还' } as const
export const claimActions: Record<string, string> = {
  CLAIM_APPLIED: '提交认领', CLAIM_ACCEPTED: '接受认领', CLAIM_REJECTED: '拒绝认领', CLAIM_CANCELLED: '取消认领',
  CLAIM_HANDOVER_CONFIRMED: '发布者确认交出', CLAIM_RECEIPT_CONFIRMED: '申请者确认收到', CLAIM_COMPLETED: '双方交接完成',
  CLAIM_RESOLVED_CONTINUE: '管理处置：继续等待', CLAIM_RESOLVED_TERMINATE: '管理处置：异常终止',
}
export interface ClaimSummary {
  id: number; item: { itemId: number; title: string; type: 'FOUND'; contentVersion: number }
  publisherId: number; applicantId: number; status: keyof typeof claimStatuses; version: number
  createdAt: string; acceptedAt: string | null; handedOverAt: string | null; receivedAt: string | null
  completedAt: string | null; endReason: string | null; canConfirm: boolean
}
export interface ClaimDetail extends ClaimSummary {
  identification: string; counterpartContact?: string | null; applicantContactSnapshot?: string; publisherContactSnapshot?: string | null
  internalNote?: string | null
  latestResolution?: { action: 'CONTINUE' | 'TERMINATE'; conclusion: string; reason: string; internalNote: string | null; occurredAt: string; actorId: number } | null
  timeline: Page<{ id: number; action: string; occurredAt: string; message: string | null }>
}
export function singleConfirmed(claim: ClaimSummary): boolean { return Boolean(claim.handedOverAt) !== Boolean(claim.receivedAt) }
export function canCancel(claim: ClaimSummary, actor: number): boolean {
  if (actor !== claim.publisherId && actor !== claim.applicantId) return false
  return (claim.status === 'APPLIED' && actor === claim.applicantId) || (claim.status === 'ACCEPTED' && !claim.handedOverAt && !claim.receivedAt)
}
export function dateTime(value: string | null): string { return value ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' }) : '尚未确认' }
