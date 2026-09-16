<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useAuthStore } from '../stores/auth'
import type { Page } from '../lib/identity'
import { claimActions, dateTime } from '../lib/claims'
import PageNavigation from '../components/PageNavigation.vue'
interface Log { id: number; objectType: string; objectId: number; action: string; actorId: number | null; occurredAt: string; beforeState: string | null; afterState: string | null; reason: string | null }
const auth = useAuthStore(), objectType = ref(''), objectId = ref(''), action = ref('')
const result = ref<Page<Log> | null>(null), loading = ref(false), error = ref('')
let sequence = 0, controller = new AbortController()
async function load(page = 1) {
  const current = ++sequence; controller.abort(); controller = new AbortController(); result.value = null; loading.value = true; error.value = ''
  const query = new URLSearchParams({ page: String(page), pageSize: '10', objectType: objectType.value, action: action.value.trim() })
  if (objectId.value && objectType.value) query.set('objectId', objectId.value)
  try { const data = await auth.client<Page<Log>>(`/api/admin/logs?${query}`, { signal: controller.signal }); if (current === sequence) result.value = data }
  catch (failure) { if (current === sequence) error.value = failure instanceof Error ? failure.message : '读取失败' }
  finally { if (current === sequence) loading.value = false }
}
onMounted(() => { void load() }); onBeforeUnmount(() => { ++sequence; controller.abort(); result.value = null })
</script>
<template><section><div class="page-heading"><div><span class="eyebrow">BUSINESS AUDIT</span><h1>业务操作日志</h1><p class="muted">此列表不展示特征证据、联系方式或内部核实结论。敏感信息仅在对应管理详情中受控读取。</p></div></div>
  <form class="search-panel item-filters" @submit.prevent="load(1)"><label>对象类型<select v-model="objectType"><option value="">全部</option><option value="USER_VERIFICATION">校园认证</option><option value="ITEM">物品</option><option value="CLAIM">认领</option></select></label><label>对象ID<input v-model="objectId" type="number" min="1" step="1" :disabled="!objectType" /></label><label>动作编码<input v-model="action" maxlength="80" placeholder="如 CLAIM_ACCEPTED" /></label><button class="primary-button" :disabled="loading">查询</button></form>
  <p v-if="error" class="error-message" role="alert">{{ error }}</p><p v-if="loading" role="status">正在读取…</p><template v-if="result"><p v-if="!result.records.length" class="state-panel">没有符合条件的日志。</p><article v-for="entry in result.records" :key="entry.id" class="form-card item-article"><h2>{{ claimActions[entry.action] || entry.action }}</h2><p>{{ entry.objectType }} #{{ entry.objectId }} · {{ dateTime(entry.occurredAt) }} · {{ entry.actorId ? `操作者 #${entry.actorId}` : '系统联动' }}</p><p>{{ entry.beforeState || '新建' }} → {{ entry.afterState }}</p><p v-if="entry.reason">{{ entry.reason }}</p></article><PageNavigation :page="result.page" :page-size="result.pageSize" :total="result.total" :busy="loading" @change="load" /></template>
</section></template>
