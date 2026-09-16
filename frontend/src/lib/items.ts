import type { Page } from './identity'
export const itemStatuses = { PENDING: '待审核', APPROVED: '已公开', REJECTED: '已驳回', CLOSED: '已关闭' } as const
export const closeReasons: Record<string, string> = { WITHDRAWN: '主动撤回', FOUND_BY_OWNER: '本人已找回', ADMIN_REMOVED: '管理下架', RETURNED: '已归还' }
export interface ImageMeta {
  id: number; mediaType: string; sizeBytes: number; width: number; height: number
  readPath: string; state: 'TEMPORARY' | 'BOUND'; createdAt: string; expiresAt: string | null
}
export interface ItemSummary {
  id: number; publisherId: number; publisherNickname: string; title: string; type: 'LOST' | 'FOUND'
  category: string | null; location: string | null; occurredAt: string | null
  status: keyof typeof itemStatuses; closeReason: string | null; createdAt: string
  version: number; contentVersion: number; images: ImageMeta[]; hasAcceptedClaim: boolean; myClaimId: number | null
}
export interface ItemDetail extends ItemSummary {
  description: string; updatedAt: string; reviewReason?: string | null; internalNote?: string | null
  timeline?: Page<{ id: number; action: string; occurredAt: string; message: string | null }>
}
export const itemActions: Record<string, string> = { ITEM_CREATED: '提交发布', ITEM_EDITED: '编辑后重新送审', ITEM_APPROVED: '审核通过', ITEM_REJECTED: '审核驳回', ITEM_CLOSED: '关闭物品' }
