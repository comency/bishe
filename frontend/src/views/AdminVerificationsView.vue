<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { RouterLink } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { verificationLabels, effectiveStatus, type Page, type AdminVerificationSummary } from '../lib/identity'
import PageNavigation from '../components/PageNavigation.vue'

const auth = useAuthStore()
const status = ref('PENDING')
const keyword = ref('')
const userId = ref('')
const result = shallowRef<Page<AdminVerificationSummary> | null>(null)
const loading = ref(false)
const error = ref('')
let sequence = 0
let controller: AbortController | undefined
onBeforeUnmount(() => { ++sequence; controller?.abort(); result.value = null })

async function load(page = 1) {
  error.value = ''
  if (userId.value && (!/^\d+$/.test(userId.value) || !Number.isSafeInteger(Number(userId.value)) || Number(userId.value) < 1)) {
    error.value = '账号 ID 必须为正整数。'; return
  }
  const current = ++sequence
  controller?.abort(); controller = new AbortController()
  loading.value = true; result.value = null
  const query = new URLSearchParams({ page: String(page), pageSize: '10' })
  if (status.value) query.set('status', status.value)
  if (keyword.value.trim()) query.set('keyword', keyword.value.trim())
  if (userId.value) query.set('userId', userId.value)
  try {
    const value = await auth.client<Page<AdminVerificationSummary>>(`/api/admin/verifications?${query}`, { signal: controller.signal })
    if (current === sequence) result.value = value
  } catch (failure) { if (current === sequence) error.value = failure instanceof Error ? failure.message : '列表加载失败。' }
  finally { if (current === sequence) loading.value = false }
}
onMounted(() => load())
</script>

<template>
  <section class="identity-page">
    <div class="page-heading"><div><span class="eyebrow">MANUAL CAMPUS REVIEW</span><h1>人工认证审核</h1><p class="muted">核对账号、申请人本人及当前在校依据，再作出审核决定。</p></div><span class="outline-badge">受控管理入口</span></div>
    <form class="search-panel verification-filters" @submit.prevent="load(1)">
      <div class="search-field"><label for="review-keyword">用户名或核对姓名</label><input id="review-keyword" v-model="keyword" type="search" maxlength="80" placeholder="输入关键词" /></div>
      <div class="type-field"><label for="review-status">当前资格</label><select id="review-status" v-model="status"><option value="">全部状态</option><option v-for="(label, value) in verificationLabels" :key="value" :value="value">{{ label }}</option></select></div>
      <div class="id-filter"><label for="review-user-id">账号 ID</label><input id="review-user-id" v-model="userId" inputmode="numeric" pattern="[0-9]*" placeholder="精确查询" /></div>
      <button class="primary-button search-button" type="submit">查询</button>
    </form>
    <p class="field-help">到期状态按当前时间判断。撤销后须先允许重核，申请人重新提交后才能再次审核；管理员不能处理自己的认证。</p>
    <div v-if="loading" class="state-panel" role="status"><span class="spinner" aria-hidden="true"></span><p>正在读取认证队列…</p></div>
    <div v-else-if="error" class="state-panel error-state" role="alert"><h2>暂时无法加载</h2><p>{{ error }}</p><button class="secondary-button" @click="load(1)">重新查询</button></div>
    <div v-else-if="result" class="surface">
      <p v-if="!result.records.length" class="empty-copy">暂无符合筛选条件的账号，请调整筛选条件。</p>
      <div v-else class="table-scroll"><table class="review-table"><thead><tr><th scope="col">账号</th><th scope="col">核对姓名</th><th scope="col">当前资格</th><th scope="col">申请轮次</th><th scope="col">有效至</th><th scope="col">操作</th></tr></thead><tbody>
        <tr v-for="entry in result.records" :key="entry.userId"><td><strong>{{ entry.username }}</strong><small>ID {{ entry.userId }}</small></td><td>{{ entry.realName || '尚无申请' }}</td><td><span :class="['status-badge', effectiveStatus(entry, auth.now).toLowerCase()]">{{ verificationLabels[effectiveStatus(entry, auth.now)] }}</span></td><td>{{ entry.applicationVersion || '—' }}</td><td>{{ entry.validThrough || '—' }}</td><td><RouterLink :to="`/admin/verifications/${entry.userId}`">查看详情 →</RouterLink><small v-if="entry.userId === auth.session?.userId">本人 · 不可自审</small></td></tr>
      </tbody></table></div>
      <PageNavigation v-bind="result" :busy="loading" @change="load" />
    </div>
  </section>
</template>
