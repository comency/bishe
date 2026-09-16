import type { RequestOptions } from '../lib/request'
import { pinia } from '../stores'
import { useAuthStore } from '../stores/auth'

export function request<T>(path: string, options?: RequestOptions): Promise<T> {
  return useAuthStore(pinia).client<T>(path, options)
}

export interface ItemSummary {
  id: number
  title: string
  description: string
  type: 'LOST' | 'FOUND'
  category?: string | null
  location?: string | null
  occurredAt?: string | null
}

export async function searchItems(keyword: string, type: string, signal?: AbortSignal): Promise<ItemSummary[]> {
  const query = new URLSearchParams({ keyword, type })
  const data = await request<unknown>(`/api/items?${query}`, { signal })
  if (!Array.isArray(data) || !data.every(item => item && Number.isSafeInteger(item.id) &&
    typeof item.title === 'string' && typeof item.description === 'string' &&
    ['LOST', 'FOUND'].includes(item.type))) throw new Error('物品列表格式不正确，请检查后端接口。')
  return data as ItemSummary[]
}
