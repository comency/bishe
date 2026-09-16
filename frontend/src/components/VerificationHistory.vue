<script setup lang="ts">
import { formatTime, verificationLabels, type VerificationApplication, type AdminVerificationApplication } from '../lib/identity'
import { useConfigStore } from '../stores/config'
defineProps<{ records: (VerificationApplication | AdminVerificationApplication)[]; admin?: boolean }>()
const settings = useConfigStore()
</script>

<template>
  <p v-if="!records.length" class="muted">暂无申请记录。</p>
  <ol v-else class="history-list">
    <li v-for="entry in records" :key="entry.id" class="history-entry">
      <div class="section-heading"><h3>第 {{ entry.applicationVersion }} 次申请</h3><span :class="['status-badge', entry.status.toLowerCase()]">{{ verificationLabels[entry.status] }}</span></div>
      <dl class="detail-grid">
        <div><dt>核对姓名</dt><dd>{{ entry.realName }}</dd></div><div><dt>学号</dt><dd>{{ entry.studentNumber || '未填写' }}</dd></div>
        <div><dt>提交时间</dt><dd>{{ formatTime(entry.submittedAt, settings.config?.timezone) }}</dd></div><div><dt>审核时间</dt><dd>{{ formatTime(entry.reviewedAt, settings.config?.timezone) }}</dd></div>
        <div v-if="entry.validThrough"><dt>该次通过的有效至日期</dt><dd>{{ entry.validThrough }}（含当天）</dd></div>
      </dl>
      <p v-if="entry.statement" class="preserve-lines">申请说明：{{ entry.statement }}</p>
      <p v-if="entry.reason" class="preserve-lines">处理原因：{{ entry.reason }}</p>
      <div v-if="admin && 'method' in entry" class="internal-note">
        <strong>管理内部记录</strong>
        <p>核验方式：{{ entry.method === 'IN_PERSON' ? '当面核验' : entry.method === 'ROSTER' ? '授权名单核对' : '未记录' }} · 审核人：{{ entry.reviewerId ?? '—' }}</p>
        <p class="preserve-lines">依据摘要：{{ entry.evidenceSummary || '—' }}</p>
        <p class="preserve-lines">内部备注：{{ entry.internalNote || '—' }}</p>
      </div>
    </li>
  </ol>
</template>
