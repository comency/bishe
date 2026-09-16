<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import type { Page } from '../lib/identity'
import { claimStatuses, dateTime, type ClaimSummary } from '../lib/claims'
import PageNavigation from '../components/PageNavigation.vue'
const route = useRoute(), auth = useAuthStore()
const admin = computed(() => route.path.startsWith('/admin/'))
const incoming = computed(() => route.path === '/claims/incoming')
const heading = computed(() => admin.value ? '认领管理' : incoming.value ? '收到的认领' : '我的认领')
const status = ref(''), itemId = ref(''), publisherId = ref(''), applicantId = ref('')
const result = ref<Page<ClaimSummary> | null>(null), loading = ref(false), error = ref('')
let sequence = 0, controller = new AbortController()
async function load(page = 1) {
  const current = ++sequence; controller.abort(); controller = new AbortController(); result.value = null; loading.value = true; error.value = ''
  const query = new URLSearchParams({ page: String(page), pageSize: '10', status: status.value })
  if (itemId.value) query.set('itemId', itemId.value)
  if (admin.value && publisherId.value) query.set('publisherId', publisherId.value)
  if (admin.value && applicantId.value) query.set('applicantId', applicantId.value)
  try {
    const data = await auth.client<Page<ClaimSummary>>(`${admin.value ? '/api/admin/claims' : incoming.value ? '/api/claims/incoming' : '/api/claims/mine'}?${query}`, { signal: controller.signal })
    if (current === sequence) result.value = data
  } catch (failure) { if (current === sequence) error.value = failure instanceof Error ? failure.message : '读取失败' }
  finally { if (current === sequence) loading.value = false }
}
watch(() => route.fullPath, () => { itemId.value = /^\d+$/.test(String(route.query.itemId ?? '')) ? String(route.query.itemId) : ''; void load() }, { immediate: true })
onBeforeUnmount(() => { ++sequence; controller.abort(); result.value = null })
</script>
<template>
  <section>
    <div class="page-heading"><div><span class="eyebrow">CLAIMS &amp; HANDOVER</span><h1>{{ heading }}</h1><p class="muted">核对物品特征后再接受申请；实际交接后，由双方分别确认。</p></div></div>
    <form class="search-panel item-filters" @submit.prevent="load(1)">
      <label>状态<select v-model="status"><option value="">全部状态</option><option v-for="(label, key) in claimStatuses" :key="key" :value="key">{{ label }}</option></select></label>
      <label>物品ID<input v-model="itemId" type="number" min="1" step="1" /></label>
      <label v-if="admin">发布者ID<input v-model="publisherId" type="number" min="1" step="1" /></label><label v-if="admin">申请者ID<input v-model="applicantId" type="number" min="1" step="1" /></label>
      <button class="primary-button" :disabled="loading">查询</button>
    </form>
    <p v-if="loading" role="status">正在读取…</p><p v-if="error" class="error-message" role="alert">{{ error }}</p>
    <template v-if="result">
      <p v-if="!result.records.length" class="state-panel">暂无符合条件的认领记录。</p>
      <div class="items-grid"><article v-for="claim in result.records" :key="claim.id" class="item-card">
        <div class="item-card-top"><span>认领 #{{ claim.id }}</span><strong>{{ claimStatuses[claim.status] }}</strong></div>
        <h2><RouterLink :to="`${admin ? '/admin/claims' : '/claims'}/${claim.id}`">{{ claim.item.title }}</RouterLink></h2>
        <p>物品 #{{ claim.item.itemId }} · 申请时内容第 {{ claim.item.contentVersion }} 版</p><p class="muted">{{ dateTime(claim.createdAt) }}</p>
        <p v-if="claim.status === 'ACCEPTED'">交出{{ claim.handedOverAt ? '已确认' : '待确认' }} · 收到{{ claim.receivedAt ? '已确认' : '待确认' }}</p><p v-if="claim.endReason">{{ claim.endReason }}</p>
      </article></div>
      <PageNavigation :page="result.page" :page-size="result.pageSize" :total="result.total" :busy="loading" @change="load" />
    </template>
  </section>
</template>
