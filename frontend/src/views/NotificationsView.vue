<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { request } from '../api'
import { dateTime } from '../lib/claims'

interface Notice { id: number; title: string; summary: string; relatedId: number; createdAt: string; readAt: string | null }
interface Page<T> { records: T[]; total: number; page: number; pageSize: number }
const records = ref<Notice[]>([]), total = ref(0), page = ref(1)
const loading = ref(false), saving = ref(false), error = ref('')
let sequence = 0, controller = new AbortController()

async function load(nextPage = 1) {
  const current = ++sequence
  controller.abort(); controller = new AbortController()
  loading.value = true; error.value = ''
  try {
    const result = await request<Page<Notice>>(`/api/notifications?page=${nextPage}&pageSize=20`, { signal: controller.signal })
    if (current === sequence) { records.value = result.records; total.value = result.total; page.value = result.page }
  } catch (failure) {
    if (current === sequence && !controller.signal.aborted) error.value = failure instanceof Error ? failure.message : '暂时无法读取通知。'
  } finally { if (current === sequence) loading.value = false }
}
async function markRead(id: number) {
  if (saving.value) return
  saving.value = true
  try { await request(`/api/notifications/${id}/read`, { method: 'POST' }); globalThis.dispatchEvent(new Event('campus-notifications-updated')); await load(page.value) }
  catch (failure) { error.value = failure instanceof Error ? failure.message : '暂时无法标记已读。' }
  finally { saving.value = false }
}
async function markAllRead() {
  if (saving.value) return
  saving.value = true
  try { await request('/api/notifications/read-all', { method: 'POST' }); globalThis.dispatchEvent(new Event('campus-notifications-updated')); await load(page.value) }
  catch (failure) { error.value = failure instanceof Error ? failure.message : '暂时无法标记已读。' }
  finally { saving.value = false }
}
onMounted(() => void load())
onBeforeUnmount(() => { ++sequence; controller.abort() })
</script>

<template>
  <section class="surface">
    <div class="page-heading"><div><span class="eyebrow">UPDATES</span><h1>站内通知</h1></div><button class="secondary-button" :disabled="saving || loading || !records.some(item => !item.readAt)" @click="markAllRead">全部标为已读</button></div>
    <p v-if="error" class="error-message" role="alert">{{ error }}</p>
    <p v-if="loading" role="status">正在读取通知…</p>
    <p v-else-if="records.length === 0" class="empty-copy">暂时没有通知。</p>
    <ol v-else class="notification-list">
      <li v-for="item in records" :key="item.id" :class="{ unread: !item.readAt }">
        <div><h2>{{ item.title }} <span v-if="!item.readAt" class="status-badge pending">未读</span></h2><p>{{ item.summary }}</p><small>{{ dateTime(item.createdAt) }}</small></div>
        <div class="button-row"><button v-if="!item.readAt" class="text-button" :disabled="saving" @click="markRead(item.id)">标为已读</button><RouterLink :to="`/claims/${item.relatedId}`" class="secondary-button" @click="!item.readAt && markRead(item.id)">查看记录</RouterLink></div>
      </li>
    </ol>
    <div v-if="total > 20" class="pagination"><span>第 {{ page }} 页 · 共 {{ total }} 条</span><div class="button-row"><button class="secondary-button" :disabled="page <= 1 || loading" @click="load(page - 1)">上一页</button><button class="secondary-button" :disabled="page * 20 >= total || loading" @click="load(page + 1)">下一页</button></div></div>
  </section>
</template>

<style scoped>
.notification-list{list-style:none;padding:0;margin:22px 0}.notification-list li{display:flex;justify-content:space-between;align-items:center;gap:20px;padding:20px 0;border-top:1px solid var(--line)}.notification-list li.unread{border-left:3px solid var(--green);padding-left:14px}.notification-list h2{font-size:16px;margin:0 0 8px}.notification-list p{margin:0 0 7px;color:var(--muted);line-height:1.8}.notification-list small{color:var(--muted)}.notification-list .button-row{flex-shrink:0}@media(max-width:650px){.notification-list li{align-items:flex-start;flex-direction:column}.notification-list .button-row{width:100%}}
</style>
