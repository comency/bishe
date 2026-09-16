<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { RouterLink } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useConfigStore } from '../stores/config'
import { ApiError } from '../lib/request'
import { canSubmitVerification, effectiveStatus, verificationLabels, type MyVerification } from '../lib/identity'
import PublicInstructions from '../components/PublicInstructions.vue'
import PageNavigation from '../components/PageNavigation.vue'
import VerificationHistory from '../components/VerificationHistory.vue'

const auth = useAuthStore()
const settings = useConfigStore()
const result = shallowRef<MyVerification | null>(null)
const page = ref(1)
const realName = ref('')
const studentNumber = ref('')
const statement = ref('')
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const success = ref('')
const controller = new AbortController()
let sequence = 0
const status = computed(() => result.value ? effectiveStatus(result.value.summary, auth.now) : null)
const canApply = computed(() => !!status.value && canSubmitVerification(status.value))
const guidance = computed(() => ({
  UNVERIFIED: '提交本人核对信息，并按下方指引完成线下人工核验。',
  PENDING: '申请已送达管理员。等待期间请勿重复提交，可刷新查看结果。',
  VERIFIED: '当前资格有效，可以进入物品大厅。截止日期包含当天，以校园时区为准。',
  REJECTED: '请查看处理原因，补充必要信息后提交新一轮申请。',
  EXPIRED: '原认证已到期，请重新提交申请。历史通过记录仍然保留。',
  REVOKED: '请联系支持渠道。须由管理员允许重新核验后，才能重新提交申请。',
}[status.value ?? 'UNVERIFIED']))
onBeforeUnmount(() => { ++sequence; controller.abort(); result.value = null; realName.value = ''; studentNumber.value = ''; statement.value = '' })

function apply(value: MyVerification) {
  result.value = value
  page.value = value.history.page
  auth.applyVerification(value.summary)
}

async function load(nextPage = page.value) {
  if (saving.value) return
  const current = ++sequence
  loading.value = true; error.value = ''
  try {
    const value = await auth.client<MyVerification>(`/api/verifications/me?page=${nextPage}&pageSize=10`, { signal: controller.signal })
    if (current !== sequence) return
    apply(value)
  } catch (failure) {
    if (current === sequence) { result.value = null; error.value = failure instanceof Error ? failure.message : '认证结果读取失败。' }
  } finally { if (current === sequence) loading.value = false }
}

async function submit() {
  if (saving.value || loading.value || !result.value || !canApply.value) return
  error.value = ''; success.value = ''
  if (!realName.value.trim()) { error.value = '请填写核对姓名。'; return }
  saving.value = true
  try {
    const value = await auth.client<MyVerification>('/api/verifications/me', { method: 'POST', signal: controller.signal,
      body: { expectedVersion: result.value.summary.version, realName: realName.value.trim(), studentNumber: studentNumber.value.trim() || null, statement: statement.value.trim() || null } })
    apply(value)
    realName.value = ''; studentNumber.value = ''; statement.value = ''
    success.value = '申请已提交，请按核验指引等待管理员人工审核。'
  } catch (failure) {
    if (controller.signal.aborted) return
    if (failure instanceof ApiError && failure.status === 409) {
      saving.value = false
      await load(1)
      error.value = '认证状态已变化，已重新查询。请根据最新结果操作，原申请不会自动重复提交。'
    } else error.value = failure instanceof Error ? failure.message : '提交失败，请先查询结果。'
  } finally { saving.value = false }
}
onMounted(() => load())
</script>

<template>
  <section class="identity-page">
    <div class="page-heading"><div><span class="eyebrow">CAMPUS IDENTITY</span><h1>校园身份认证</h1><p class="muted">由管理员核对当前在校身份，让校园互助安心有序。</p></div><button class="secondary-button" :disabled="loading || saving" @click="load()">{{ loading ? '读取中…' : '刷新认证结果' }}</button></div>
    <p v-if="auth.notice" class="notice" role="status">{{ auth.notice }}</p>
    <p v-if="error" class="error-message" role="alert">{{ error }}</p><p v-if="success" class="notice" role="status">{{ success }}</p>
    <div v-if="loading && !result" class="state-panel" role="status"><span class="spinner" aria-hidden="true"></span><p>正在读取当前资格与申请历史…</p></div>
    <div v-if="result && status" class="identity-layout">
      <div>
        <section class="surface">
          <div class="section-heading"><h2>当前校园资格</h2><span :class="['status-badge', status.toLowerCase()]">{{ verificationLabels[status] }}</span></div>
          <p class="body-copy">{{ guidance }}</p>
          <p v-if="result.summary.reason" class="notice preserve-lines">处理原因：{{ result.summary.reason }}</p>
          <p v-if="result.summary.validThrough" class="field-help">最近认证有效至 {{ result.summary.validThrough }}（含当天，{{ settings.config?.timezone || '校园时区' }}）。</p>
          <RouterLink v-if="status === 'VERIFIED'" class="primary-button inline-button" to="/items">进入物品大厅 →</RouterLink>
          <form v-if="canApply" class="auth-form" @submit.prevent="submit">
            <label for="verification-name">本人核对姓名 <small>必填，最多 80 字</small></label><input id="verification-name" v-model="realName" required maxlength="80" autocomplete="off" :disabled="saving || loading" />
            <label for="verification-number">学号 <small>按核验依据选填</small></label><input id="verification-number" v-model="studentNumber" maxlength="40" autocomplete="off" :disabled="saving || loading" />
            <label for="verification-statement">申请说明 <small>选填，最多 500 字</small></label><textarea id="verification-statement" v-model="statement" maxlength="500" rows="4" :disabled="saving || loading"></textarea>
            <p class="field-help">姓名、学号仅供受控核对，不向大厅展示。请勿填写密码或上传证件原图。</p>
            <button class="primary-button" type="submit" :disabled="saving || loading">{{ saving ? '提交中…' : status === 'UNVERIFIED' ? '提交人工核验申请' : '提交新的核验申请' }}</button>
          </form>
          <div v-else-if="status === 'PENDING' && result.currentApplication" class="current-application">
            <p>当前为第 {{ result.currentApplication.applicationVersion }} 次申请，核对姓名：{{ result.currentApplication.realName }}。</p>
            <p class="field-help">待审申请暂不可编辑，审核结果会保留在下方历史中。</p>
          </div>
        </section>
        <section class="surface history-surface"><div class="section-heading"><h2>申请与审核历史</h2></div><p class="field-help">历史记录保留当时的结论；到期、撤销或允许重核不覆盖已通过的事实。</p><VerificationHistory :records="result.history.records" /><PageNavigation v-bind="result.history" :busy="loading || saving" @change="load" /></section>
      </div>
      <aside><PublicInstructions /><div v-if="auth.isAdmin" class="development-note"><strong>管理员账号说明</strong><p>管理审核不要求学生认证；参与普通失物招领仍需有效认证，且不能审核自己的申请。</p><RouterLink to="/admin/verifications">进入认证审核 →</RouterLink></div></aside>
    </div>
    <PublicInstructions v-else-if="!loading" />
  </section>
</template>
