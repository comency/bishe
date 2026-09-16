<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { claimStatuses, claimActions, canCancel, singleConfirmed, dateTime, type ClaimDetail } from '../lib/claims'
import PageNavigation from '../components/PageNavigation.vue'
const route = useRoute(), auth = useAuthStore()
const admin = computed(() => route.path.startsWith('/admin/')), actor = computed(() => auth.session?.userId ?? 0)
const claim = ref<ClaimDetail | null>(null), loading = ref(false), saving = ref(false), stale = ref(false), error = ref(''), notice = ref('')
const contact = ref(''), reason = ref(''), conclusion = ref(''), internalNote = ref(''), resolution = ref('CONTINUE'), checked = ref({ accept: false, end: false, confirm: false, resolve: false })
const publisher = computed(() => claim.value?.publisherId === actor.value)
const base = computed(() => `${admin.value ? '/api/admin/claims' : '/api/claims'}/${String(route.params.id)}`)
let sequence = 0, controller = new AbortController()
function reset() { contact.value = ''; reason.value = ''; conclusion.value = ''; internalNote.value = ''; resolution.value = 'CONTINUE'; checked.value = { accept: false, end: false, confirm: false, resolve: false } }
async function load(page = 1) {
  if (saving.value) return
  const current = ++sequence; controller.abort(); controller = new AbortController(); claim.value = null; reset(); loading.value = true; error.value = ''; stale.value = false
  try { const data = await auth.client<ClaimDetail>(`${base.value}?timelinePage=${page}&timelinePageSize=10`, { signal: controller.signal }); if (current === sequence) claim.value = data }
  catch (failure) { if (current === sequence) error.value = failure instanceof Error ? failure.message : '读取失败' }
  finally { if (current === sequence) loading.value = false }
}
async function act(action: 'accept' | 'reject' | 'cancel' | 'confirm-handover' | 'confirm-receipt' | 'resolve') {
  if (!claim.value || saving.value || stale.value || !checked.value[action === 'accept' ? 'accept' : action === 'resolve' ? 'resolve' : action.startsWith('confirm-') ? 'confirm' : 'end']) return
  const current = sequence; saving.value = true; error.value = ''; notice.value = ''
  const body = action.startsWith('confirm-') ? undefined : action === 'accept' ? { expectedVersion: claim.value.version, contact: contact.value.trim() }
    : action === 'resolve' ? { expectedVersion: claim.value.version, action: resolution.value, conclusion: conclusion.value.trim(), reason: reason.value.trim(), internalNote: internalNote.value.trim() || null }
      : { expectedVersion: claim.value.version, reason: reason.value.trim() }
  try {
    const data = await auth.client<ClaimDetail>(`${base.value}/${action}`, { method: 'POST', ...(body ? { body } : {}), signal: controller.signal })
    if (current === sequence) { claim.value = data; reset(); notice.value = '操作已保存。' }
  } catch (failure) {
    if (current === sequence) { claim.value = null; stale.value = true; reset(); error.value = failure instanceof Error ? failure.message : '操作结果未确认，请刷新后核对，勿重复提交。' }
  } finally { if (current === sequence) saving.value = false }
}
watch(() => route.fullPath, () => { notice.value = ''; void load() }, { immediate: true })
onBeforeUnmount(() => { ++sequence; controller.abort(); claim.value = null; reset() })
</script>
<template>
  <section class="item-detail">
    <div class="page-heading"><div><span class="eyebrow">TWO-WAY HANDOVER</span><h1>{{ admin ? '认领管理详情' : '认领详情' }}</h1></div><RouterLink :to="admin ? '/admin/claims' : '/claims/mine'" class="secondary-button">返回列表</RouterLink></div>
    <p v-if="error" class="error-message" role="alert">{{ error }}</p><p v-if="notice" class="success-message" role="status">{{ notice }}</p>
    <button class="text-button" :disabled="saving || loading" @click="load()">刷新最新记录</button><p v-if="loading" role="status">正在读取…</p>
    <template v-if="claim">
      <article class="form-card item-article">
        <div class="item-card-top"><span>认领 #{{ claim.id }}</span><strong>{{ claimStatuses[claim.status] }}</strong></div><h2>{{ claim.item.title }}</h2>
        <p class="muted">申请时物品 #{{ claim.item.itemId }} · 内容第 {{ claim.item.contentVersion }} 版 · 认领版本 {{ claim.version }}</p>
        <h3>认领特征说明</h3><p class="item-full-description">{{ claim.identification }}</p>
        <p>发布者确认交出：{{ dateTime(claim.handedOverAt) }}</p><p>申请者确认收到：{{ dateTime(claim.receivedAt) }}</p>
        <p v-if="claim.completedAt">双方完成时间：{{ dateTime(claim.completedAt) }}</p><p v-if="claim.endReason">结束原因：{{ claim.endReason }}</p>
        <p v-if="!admin && claim.status === 'ACCEPTED'">对方本次交接联系方式：<strong>{{ claim.counterpartContact }}</strong></p>
        <p v-if="!admin && claim.status !== 'ACCEPTED'" class="muted">联系方式仅在交接中对双方开放，结束后隐藏。</p>
        <template v-if="admin"><h3>受控联系快照</h3><p>申请者：{{ claim.applicantContactSnapshot }}</p><p>发布者：{{ claim.publisherContactSnapshot || '尚未接受' }}</p></template>
        <p v-if="claim.status === 'ACCEPTED' && singleConfirmed(claim)" class="environment-notice">当前仅有一方确认，尚未完成归还。不能直接取消；遇到争议或资格问题请联系管理员。</p>
        <p v-if="!admin && claim.status === 'ACCEPTED' && !claim.canConfirm" class="muted">您已完成本方确认，或当前双方交接资格未满足；请刷新核对并等待处理。</p>
      </article>
      <form v-if="!admin && publisher && claim.status === 'APPLIED'" class="form-card item-form" @submit.prevent="act('accept')"><h2>接受本次认领</h2><p>请先核对特征说明。接受后，其他申请仍保留，但不能同时接受。</p><fieldset :disabled="saving || stale"><label>本次交接联系方式<input v-model="contact" required maxlength="100" autocomplete="off" /></label><label class="check-label"><input v-model="checked.accept" required type="checkbox" />已核对特征，并同意向对方提供本次联系方式</label><button class="primary-button">接受认领</button></fieldset></form>
      <form v-if="!admin && ((publisher && claim.status === 'APPLIED') || canCancel(claim, actor))" class="form-card item-form" @submit.prevent="act(publisher && claim.status === 'APPLIED' ? 'reject' : 'cancel')"><h2>{{ publisher && claim.status === 'APPLIED' ? '拒绝申请' : '取消本次认领' }}</h2><fieldset :disabled="saving || stale"><label>原因<textarea v-model="reason" required maxlength="500" /></label><label class="check-label"><input v-model="checked.end" required type="checkbox" />确认结束本次申请；同一物品不能再次申请</label><button class="secondary-button">{{ publisher && claim.status === 'APPLIED' ? '拒绝申请' : '取消认领' }}</button></fieldset></form>
      <form v-if="!admin && claim.canConfirm" class="form-card item-form" @submit.prevent="act(publisher ? 'confirm-handover' : 'confirm-receipt')"><h2>{{ publisher ? '确认已交出物品' : '确认已收到物品' }}</h2><p>仅在实际交接完成后确认。本方确认不可撤回，双方确认后系统才会标记已归还。</p><fieldset :disabled="saving || stale"><label class="check-label"><input v-model="checked.confirm" type="checkbox" required />{{ publisher ? '我已将物品实际交给申请者' : '我已实际收到并核对物品' }}</label><button class="primary-button">{{ publisher ? '确认交出' : '确认收到' }}</button></fieldset></form>
      <form v-if="admin && claim.status === 'ACCEPTED' && singleConfirmed(claim)" class="form-card item-form" @submit.prevent="act('resolve')"><h2>单方交接异常处置</h2><p>管理员不能代替双方确认。“继续”等待双方自行处理；“终止”保留已有确认事实，并下架物品、结束其余申请。</p><fieldset :disabled="saving || stale"><label>处置决定<select v-model="resolution"><option value="CONTINUE">继续等待</option><option value="TERMINATE">异常终止并下架</option></select></label><label>核实结论（仅管理可读）<textarea v-model="conclusion" required maxlength="500" /></label><label>告知参与者的原因<textarea v-model="reason" required maxlength="500" /></label><label>内部备注<textarea v-model="internalNote" maxlength="500" /></label><label class="check-label"><input v-model="checked.resolve" required type="checkbox" />已核实情况并确认本次处置</label><button class="primary-button">提交异常处置</button></fieldset></form>
      <article v-if="admin && claim.latestResolution" class="form-card item-article"><h2>最近异常处置</h2><p>{{ claim.latestResolution.action === 'CONTINUE' ? '继续等待' : '异常终止' }} · {{ dateTime(claim.latestResolution.occurredAt) }} · 管理员 #{{ claim.latestResolution.actorId }}</p><p>核实结论：{{ claim.latestResolution.conclusion }}</p><p>告知原因：{{ claim.latestResolution.reason }}</p><p>内部备注：{{ claim.latestResolution.internalNote || '无' }}</p></article>
      <article class="form-card item-article"><h2>认领操作历史</h2><ol><li v-for="event in claim.timeline.records" :key="event.id"><strong>{{ claimActions[event.action] || event.action }}</strong> · {{ dateTime(event.occurredAt) }}<p v-if="event.message">{{ event.message }}</p></li></ol><PageNavigation :page="claim.timeline.page" :page-size="claim.timeline.pageSize" :total="claim.timeline.total" :busy="loading || saving" @change="load" /></article>
    </template>
  </section>
</template>
