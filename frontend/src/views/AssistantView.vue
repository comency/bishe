<script setup lang="ts">
import { ref } from 'vue'
import { RouterLink } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useConfigStore } from '../stores/config'
import { aiReasons, useAiRequest } from '../lib/ai'
const auth = useAuthStore(), config = useConfigStore(), question = ref('')
const { result, snapshot, busy, error, run, clear } = useAiRequest('chat', () => question.value, auth.client)
</script>
<template>
  <section class="assistant-page">
    <div class="page-heading"><div><span class="eyebrow">CAMPUS GUIDE</span><h1>智能使用助手</h1><p class="muted">模型仅从已审核的流程语句中选择相关说明，不自由编写业务规则。不会查询您的记录、判断归属或代您发布、审批及确认交接。</p></div></div>
    <p class="environment-notice">{{ config.config?.aiEnabled ? '已允许尝试本地模型，仍可能因资源、繁忙或超时不可用。' : '模型当前未启用；仍可使用下方静态帮助，手动业务不受影响。' }} 请勿输入姓名、学号、联系方式、认证材料或私密认领证据。</p>
    <form class="form-card item-form" @submit.prevent="run"><label>想了解哪个操作？<textarea v-model="question" required maxlength="3000" rows="4" placeholder="例如：认领被接受后，双方如何确认归还？" /></label><div class="button-row"><button type="submit" class="primary-button" :disabled="busy">{{ busy ? '等待建议…' : '询问使用方法' }}</button><button v-if="busy || result" type="button" class="text-button" @click="clear">{{ busy ? '取消等待' : '清除本次回答' }}</button></div></form>
    <p v-if="error" class="error-message" role="alert">{{ error }}</p>
    <article v-if="result" class="form-card item-article"><h2>{{ result.status === 'GENERATED' ? '使用建议（请核对）' : `暂不可用 · ${aiReasons[result.reason]}` }}</h2><p class="muted">本次问题：{{ snapshot }}</p><p class="item-full-description">{{ result.content }}</p><p class="muted">{{ result.status === 'GENERATED' ? '以上是模型选取、系统校验后展示的流程语句，仍需核对是否切题。不代表系统已经查询或执行任何操作，以实际页面状态为准。' : '这是降级提示，不是模型生成的回答。可继续使用静态帮助。' }}</p></article>
    <article class="form-card item-article"><h2>静态使用说明</h2><p>这些是系统流程说明，不是AI生成内容。</p><ol>
      <li>先在<RouterLink to="/verification">校园认证</RouterLink>提交申请，由管理员人工核验。登录不等于在校身份已通过。</li>
      <li>在<RouterLink to="/items/new">发布启事</RouterLink>填写物品事实，人工内容审核通过后才公开。修改内容会重新送审。</li>
      <li>在<RouterLink to="/items">物品大厅</RouterLink>查找线索。只能申请他人已公开的招领启事，同人同物只申请一次。</li>
      <li>发布者接受后才向双方开放本次交接联系方式。可在<RouterLink to="/claims/mine">我的认领</RouterLink>或<RouterLink to="/claims/incoming">收到的认领</RouterLink>查看进展。</li>
      <li>实际交接后，发布者确认交出、申请者确认收到；只有双方确认才显示已归还。已有单方确认不能直接取消，有争议请联系支持人员。</li>
      <li>资格过期或被撤销后须按认证页面说明处理。智能助手不能恢复资格或替管理员审核。</li>
    </ol><p>支持渠道：{{ config.config?.supportContact || '请查看校园认证页面的支持说明。' }}</p></article>
  </section>
</template>
