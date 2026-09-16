<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { itemActions, itemStatuses, closeReasons, type ItemDetail } from '../lib/items'
import PrivateImage from '../components/PrivateImage.vue'
import PageNavigation from '../components/PageNavigation.vue'
const route = useRoute(), auth = useAuthStore()
const admin = computed(() => route.path.startsWith('/admin/'))
const item = ref<ItemDetail | null>(null), loading = ref(false), saving = ref(false), stale = ref(false), error = ref(''), notice = ref('')
const decision = ref('APPROVED'), reason = ref(''), internalNote = ref(''), closeReason = ref('WITHDRAWN'), confirmed = ref(false)
const closureNote = ref(''), closureConfirmed = ref(false)
const matches = ref<{ id: number; title: string; score: number }[]>([])
const owner = computed(() => item.value?.publisherId === auth.session?.userId)
const base = computed(() => `${admin.value ? '/api/admin/items' : '/api/items'}/${String(route.params.id)}`)
let sequence = 0
let controller = new AbortController()
function reset() { reason.value = ''; internalNote.value = ''; decision.value = 'APPROVED'; closeReason.value = 'WITHDRAWN'; confirmed.value = false; closureNote.value = ''; closureConfirmed.value = false }
async function load(page = 1) {
  if (saving.value) return
  const current = ++sequence; controller.abort(); controller = new AbortController(); loading.value = true; error.value = ''; item.value = null; stale.value = false; matches.value = []; reset()
  try {
    const result = await auth.client<ItemDetail>(`${base.value}?timelinePage=${page}&timelinePageSize=10`, { signal: controller.signal })
    if (current === sequence) item.value = result
  } catch (failure) { if (current === sequence) error.value = failure instanceof Error ? failure.message : '读取失败' }
  finally { if (current === sequence) loading.value = false }
}
async function act(action: 'review' | 'close') {
  if (!item.value || saving.value || stale.value || !(action === 'review' ? confirmed.value : closureConfirmed.value)) return
  const current = sequence; saving.value = true; error.value = ''; notice.value = ''
  const review = action === 'review'
  const body = review ? { status: decision.value, expectedVersion: item.value.version, ...(decision.value !== 'APPROVED' ? { reason: reason.value.trim() } : {}), internalNote: internalNote.value.trim() || null }
    : { expectedVersion: item.value.version, reason: closureNote.value.trim(), ...(!admin.value ? { closeReason: closeReason.value } : {}) }
  try {
    const result = await auth.client<ItemDetail>(`${base.value}/${action}`, { method: review ? 'PUT' : 'POST', body, signal: controller.signal })
    if (current === sequence) { item.value = result; matches.value = []; reset(); notice.value = '操作已保存。' }
  } catch (failure) { if (current === sequence) { stale.value = true; error.value = failure instanceof Error ? failure.message : '操作失败，请刷新确认结果' } }
  finally { if (current === sequence) saving.value = false }
}
async function findMatches() {
  const current = sequence
  try { const result = await auth.client<typeof matches.value>(`${base.value}/matches`, { signal: controller.signal }); if (current === sequence) { matches.value = result; if (!result.length) notice.value = '暂无非零相似度候选物品。' } }
  catch (failure) { if (current === sequence) error.value = failure instanceof Error ? failure.message : '匹配读取失败' }
}
watch(() => route.fullPath, () => { notice.value = ''; void load() }, { immediate: true })
onBeforeUnmount(() => { ++sequence; controller.abort(); item.value = null; matches.value = []; reset() })
</script>
<template>
  <section class="item-detail">
    <div class="page-heading"><div><span class="eyebrow">{{ admin ? 'CONTENT REVIEW' : 'ITEM DETAILS' }}</span><h1>{{ admin ? '物品审核详情' : '物品详情' }}</h1></div><RouterLink :to="admin ? '/admin/items' : '/items'" class="secondary-button">返回列表</RouterLink></div>
    <p v-if="error" class="error-message" role="alert">{{ error }}</p><p v-if="notice" class="success-message" role="status">{{ notice }}</p>
    <button v-if="error || item" class="text-button" :disabled="saving || loading" @click="load()">刷新最新记录</button>
    <p v-if="loading" role="status">正在读取…</p>
    <template v-if="item">
      <article class="form-card item-article">
        <div class="item-card-top"><span class="item-type">{{ item.type === 'LOST' ? '寻物启事' : '招领启事' }}</span><span>{{ itemStatuses[item.status] }}{{ item.closeReason ? ` · ${closeReasons[item.closeReason]}` : '' }}</span></div>
        <h2>{{ item.title }}</h2><p class="muted">{{ item.publisherNickname }} · {{ item.category || '未分类' }} · {{ item.location || '地点未填写' }} · {{ item.occurredAt || '日期未填写' }}</p>
        <p class="item-full-description">{{ item.description }}</p>
        <div class="image-gallery"><PrivateImage v-for="image in item.images" :id="image.id" :key="image.id" :alt="item.title" /></div>
        <p class="muted">物品 #{{ item.id }} · 记录版本 {{ item.version }} · 内容第 {{ item.contentVersion }} 版</p>
        <p v-if="item.reviewReason">审核反馈：{{ item.reviewReason }}</p><p v-if="admin && item.internalNote">管理内部备注：{{ item.internalNote }}</p>
        <div v-if="!admin" class="button-row"><RouterLink v-if="owner && item.status !== 'CLOSED'" :to="`/items/${item.id}/edit`" class="secondary-button">编辑并重新送审</RouterLink><button class="secondary-button" @click="findMatches">查找相似物品</button></div>
        <p v-if="!admin" class="item-footnote">本阶段不公开联系方式；认领申请与双向交接将在下一阶段接入。</p>
        <ul v-if="matches.length"><li v-for="match in matches" :key="match.id"><RouterLink :to="`/items/${match.id}`">{{ match.title }}</RouterLink> · 相似度 {{ match.score }}%</li></ul>
      </article>
      <form v-if="admin && item.status === 'PENDING'" class="form-card item-form" @submit.prevent="act('review')">
        <h2>审核当前内容</h2>
        <fieldset :disabled="saving || stale"><label>决定<select v-model="decision"><option value="APPROVED">通过并公开</option><option value="REJECTED">驳回修改</option></select></label><label v-if="decision === 'REJECTED'">驳回原因<textarea v-model="reason" required maxlength="500" /></label><label>内部备注（仅管理员可读）<textarea v-model="internalNote" maxlength="500" /></label><label class="check-label"><input v-model="confirmed" type="checkbox" required />已核对本页当前内容及图片</label><button class="primary-button" type="submit">{{ saving ? '处理中…' : '提交审核决定' }}</button></fieldset>
      </form>
      <form v-if="(owner || admin) && item.status !== 'CLOSED'" class="form-card item-form" @submit.prevent="act('close')">
        <h2>{{ admin ? '管理下架' : '关闭启事' }}</h2><p class="muted">关闭后不能重开，也不会继续出现在大厅。</p>
        <fieldset :disabled="saving || stale"><label v-if="!admin">关闭方式<select v-model="closeReason"><option value="WITHDRAWN">主动撤回</option><option v-if="item.type === 'LOST'" value="FOUND_BY_OWNER">本人已找回</option></select></label><label>原因<textarea v-model="closureNote" required maxlength="500" /></label><label class="check-label"><input v-model="closureConfirmed" type="checkbox" required />确认关闭当前记录</label><button class="secondary-button" type="submit">{{ saving ? '处理中…' : '确认关闭' }}</button></fieldset>
      </form>
      <section v-if="item.timeline" class="form-card item-article"><h2>操作历史</h2><ol><li v-for="event in item.timeline.records" :key="event.id"><strong>{{ itemActions[event.action] || event.action }}</strong> · <time>{{ new Date(event.occurredAt).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' }) }}</time><p v-if="event.message">{{ event.message }}</p></li></ol><p v-if="!item.timeline.records.length">尚无历史记录（旧数据不会补造历史）。</p><PageNavigation :page="item.timeline.page" :page-size="item.timeline.pageSize" :total="item.timeline.total" :busy="loading || saving" @change="load" /></section>
    </template>
  </section>
</template>
