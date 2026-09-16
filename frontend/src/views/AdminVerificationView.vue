<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useConfigStore } from '../stores/config'
import { ApiError } from '../lib/request'
import { campusToday, effectiveStatus, verificationLabels, type AdminVerification } from '../lib/identity'
import PageNavigation from '../components/PageNavigation.vue'
import VerificationHistory from '../components/VerificationHistory.vue'

const route = useRoute()
const auth = useAuthStore()
const settings = useConfigStore()
const result = shallowRef<AdminVerification | null>(null)
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const success = ref('')
const decision = ref<'APPROVED' | 'REJECTED'>('APPROVED')
const method = ref<'IN_PERSON' | 'ROSTER'>('IN_PERSON')
const evidenceSummary = ref('')
const validThrough = ref('')
const reason = ref('')
const internalNote = ref('')
const confirmed = ref(false)
let sequence = 0
let controller = new AbortController()
const status = computed(() => result.value ? effectiveStatus(result.value.summary, auth.now) : null)
const self = computed(() => result.value?.summary.userId === auth.session?.userId)
const today = computed(() => campusToday(settings.config?.timezone))
const hasAction = computed(() => status.value && ['PENDING', 'VERIFIED', 'REVOKED'].includes(status.value) && !self.value)
const actionLabel = computed(() => status.value === 'PENDING' ? decision.value === 'APPROVED' ? '确认通过本次申请' : '确认驳回本次申请' : status.value === 'VERIFIED' ? '确认撤销校园资格' : '允许重新提交核验')

function resetForm() { decision.value = 'APPROVED'; method.value = 'IN_PERSON'; evidenceSummary.value = ''; validThrough.value = ''; reason.value = ''; internalNote.value = ''; confirmed.value = false }
onBeforeUnmount(() => { ++sequence; controller.abort(); result.value = null; resetForm() })

async function load(page = 1) {
  if (saving.value) return
  const current = ++sequence
  controller.abort(); controller = new AbortController()
  loading.value = true; error.value = ''; result.value = null; resetForm()
  const id = String(route.params.userId)
  if (!Number.isSafeInteger(Number(id)) || Number(id) < 1) { error.value = '账号 ID 不合法。'; loading.value = false; return }
  try {
    const value = await auth.client<AdminVerification>(`/api/admin/verifications/${id}?page=${page}&pageSize=10`, { signal: controller.signal })
    if (current === sequence) result.value = value
  } catch (failure) { if (current === sequence) error.value = failure instanceof Error ? failure.message : '管理详情读取失败。' }
  finally { if (current === sequence) loading.value = false }
}

async function submit() {
  if (saving.value || loading.value || !result.value || !hasAction.value) return
  error.value = ''; success.value = ''
  const summary = result.value.summary
  const application = result.value.currentApplication
  let path: string
  let body: object
  if (status.value === 'PENDING') {
    if (!application || application.status !== 'PENDING') { error.value = '当前申请不再待审，请刷新。'; return }
    path = `/api/admin/verifications/${summary.userId}/review`
    const base = { applicationId: application.id, expectedVersion: summary.version, decision: decision.value, internalNote: internalNote.value.trim() || null }
    if (decision.value === 'APPROVED') {
      if (!evidenceSummary.value.trim() || !validThrough.value || !confirmed.value) { error.value = '请填写核验依据、有效至日期，并确认已完成本人及在校身份核对。'; return }
      if (validThrough.value < today.value) { error.value = '有效至日期不能早于校园当天。'; return }
      body = { ...base, method: method.value, evidenceSummary: evidenceSummary.value.trim(), validThrough: validThrough.value }
    } else {
      if (!reason.value.trim()) { error.value = '请填写申请人可理解的驳回原因。'; return }
      body = { ...base, reason: reason.value.trim() }
    }
  } else {
    if (!reason.value.trim()) { error.value = '请填写本次处理原因。'; return }
    path = `/api/admin/verifications/${summary.userId}/${status.value === 'VERIFIED' ? 'revoke' : 'reopen'}`
    body = { expectedVersion: summary.version, reason: reason.value.trim() }
  }
  saving.value = true
  const current = sequence
  try {
    const value = await auth.client<AdminVerification>(path, { method: 'POST', body, signal: controller.signal })
    if (current !== sequence) return
    result.value = value
    resetForm()
    success.value = value.summary.status === 'UNVERIFIED' ? '已允许重核。申请人仍需重新提交并经人工审核，当前未恢复业务资格。' : '处理已保存，以下为最新资格与审核记录。'
  } catch (failure) {
    if (current !== sequence || controller.signal.aborted) return
    if (failure instanceof ApiError && failure.status === 409) {
      saving.value = false
      await load(1)
      error.value = '申请或资格已变化，旧审核表单已清空并重新读取。请重新核对；本次操作不会自动重放。'
    } else {
      if (failure instanceof ApiError && [403, 404].includes(failure.status)) { result.value = null; resetForm() }
      error.value = failure instanceof Error ? failure.message : '处理失败，请先查询最新记录。'
    }
  } finally { if (current === sequence || !saving.value) saving.value = false }
}

watch(() => route.params.userId, () => {
  // Changing the selected account invalidates all previous reads and writes in this view.
  ++sequence; controller.abort(); saving.value = false; success.value = ''; void load(1)
}, { immediate: true })
</script>

<template>
  <section class="identity-page">
    <RouterLink to="/admin/verifications" class="back-link">← 返回认证审核队列</RouterLink>
    <div class="page-heading"><div><span class="eyebrow">VERIFICATION REVIEW</span><h1>认证核对详情</h1><p class="muted">核验依据仅供管理读取，面向本人原因与内部备注分开记录。</p></div><button class="secondary-button" :disabled="loading || saving" @click="load(1)">刷新详情</button></div>
    <p v-if="error" class="error-message" role="alert">{{ error }}</p><p v-if="success" class="notice" role="status">{{ success }}</p>
    <div v-if="loading" class="state-panel" role="status"><span class="spinner" aria-hidden="true"></span><p>正在读取当前申请与受控历史…</p></div>
    <template v-if="result && status">
      <div class="identity-layout admin-detail-layout">
        <section class="surface">
          <div class="section-heading"><h2>{{ result.summary.username }}</h2><span :class="['status-badge', status.toLowerCase()]">{{ verificationLabels[status] }}</span></div>
          <dl class="detail-grid"><div><dt>账号 ID</dt><dd>{{ result.summary.userId }}</dd></div><div><dt>当前申请轮次</dt><dd>{{ result.summary.applicationVersion || '尚无申请' }}</dd></div><div><dt>资格并发版本</dt><dd>{{ result.summary.version }}</dd></div><div><dt>最近有效至</dt><dd>{{ result.summary.validThrough || '—' }}</dd></div></dl>
          <p v-if="result.summary.reason" class="notice preserve-lines">最近处理原因：{{ result.summary.reason }}</p>
          <template v-if="result.currentApplication"><h3>最近提交的申请</h3><dl class="detail-grid"><div><dt>核对姓名</dt><dd>{{ result.currentApplication.realName }}</dd></div><div><dt>学号</dt><dd>{{ result.currentApplication.studentNumber || '未填写' }}</dd></div></dl><p class="preserve-lines">{{ result.currentApplication.statement || '未填写补充说明。' }}</p></template>
          <p v-else class="muted">此账号尚未提交过认证申请。</p>
          <p class="field-help">申请轮次与资格并发版本分别记录；审核仅作用于当前待审申请。</p>
        </section>
        <section class="surface">
          <h2>人工处理</h2>
          <p v-if="self" class="notice">这是当前管理员自己的账号，不允许自审、撤销本人资格或允许本人重核。请由另一位管理员处理。</p>
          <p v-else-if="!hasAction" class="muted">当前状态无需管理动作，等待本人提交新的申请。历史审核结论保持原样。</p>
          <form v-else class="auth-form" @submit.prevent="submit">
            <template v-if="status === 'PENDING'">
              <label for="review-decision">审核决定</label><select id="review-decision" v-model="decision" :disabled="saving"><option value="APPROVED">通过</option><option value="REJECTED">驳回并补充信息</option></select>
              <template v-if="decision === 'APPROVED'">
                <label for="review-method">实际核验方式</label><select id="review-method" v-model="method" :disabled="saving"><option value="IN_PERSON">当面核验</option><option value="ROSTER">获授权名单核对</option></select>
                <label for="review-evidence">核验依据摘要 <small>内部可见，必填</small></label><textarea id="review-evidence" v-model="evidenceSummary" required maxlength="500" rows="3" :disabled="saving"></textarea>
                <label for="review-until">有效至日期 <small>包含当天</small></label><input id="review-until" v-model="validThrough" type="date" :min="today" required :disabled="saving" />
                <p class="field-help">按 {{ settings.config?.timezone || '校园时区' }} 计算次日零点到期。请依据真实核验结果确定期限。</p>
                <label class="checkbox-label"><input v-model="confirmed" type="checkbox" required :disabled="saving" />已核对账号对应本人及当前在校身份</label>
              </template>
            </template>
            <p v-if="status === 'VERIFIED'" class="notice">撤销后，该账号立即失去普通业务准入；历史记录保留，重新申请须先由管理员允许重核。</p>
            <p v-if="status === 'REVOKED'" class="notice">允许重核只恢复提交申请的入口，不会直接恢复认证通过或普通业务资格。</p>
            <template v-if="status !== 'PENDING' || decision === 'REJECTED'"><label for="review-reason">面向申请人的处理原因 <small>必填，最多 500 字</small></label><textarea id="review-reason" v-model="reason" required maxlength="500" rows="3" :disabled="saving"></textarea></template>
            <template v-if="status === 'PENDING'"><label for="review-note">内部备注 <small>选填，不向申请人展示</small></label><textarea id="review-note" v-model="internalNote" maxlength="500" rows="2" :disabled="saving"></textarea></template>
            <button :class="['primary-button', { 'danger-button': status === 'VERIFIED' }]" type="submit" :disabled="saving">{{ saving ? '提交中…' : actionLabel }}</button>
          </form>
        </section>
      </div>
      <section class="surface history-surface"><h2>受控申请历史</h2><VerificationHistory :records="result.history.records" admin /><PageNavigation v-bind="result.history" :busy="loading || saving" @change="load" /></section>
    </template>
  </section>
</template>
