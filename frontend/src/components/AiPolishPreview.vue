<script setup lang="ts">
import { useAuthStore } from '../stores/auth'
import { aiReasons, useAiRequest } from '../lib/ai'
const props = defineProps<{ text: string; disabled?: boolean }>()
const emit = defineEmits<{ apply: [content: string] }>()
const auth = useAuthStore()
const { result, snapshot, busy, error, canApply, run, clear } = useAiRequest('polish', () => props.text, auth.client)
function apply() { if (!props.disabled && canApply.value && result.value) { const text = result.value.content; clear(); emit('apply', text) } }
</script>
<template>
  <section class="ai-panel" aria-label="文案辅助预览">
    <h2>文案辅助</h2><p class="muted">保守整理模式：仅调整标点与空白，保留原有文字和数字，结果须自行核对。仅发送当前详细描述，不发送图片、联系人、认证材料或认领证据。请勿输入个人敏感信息。</p>
    <button type="button" class="secondary-button" :disabled="disabled || busy || !text.trim()" @click="run">{{ busy ? '生成预览中…' : '预览润色建议' }}</button>
    <button v-if="busy" type="button" class="text-button" @click="clear">取消等待</button>
    <p v-if="busy" role="status">可以继续编辑原文；结果不会自动覆盖或提交表单。</p>
    <p v-if="error" class="error-message" role="alert">{{ error }}</p>
    <template v-if="result">
      <p v-if="result.status === 'UNAVAILABLE'" class="environment-notice" role="status">{{ aiReasons[result.reason] }}：{{ result.content }} 此提示不是模型生成结果。</p>
      <template v-else><p class="muted">模型建议，未经事实核验。请检查日期、地点及特征，不代表已发布或审核通过。</p><p class="item-full-description ai-preview">{{ result.content }}</p>
        <p v-if="text !== snapshot" role="status">原文已变化，本次预览不能采用。请重新生成，避免覆盖新内容。</p>
        <p v-if="result.content.length > 3000" role="alert">建议超过正文长度限制，不能直接采用。</p>
        <button type="button" class="secondary-button" :disabled="disabled || !canApply" @click="apply">核对后采用到描述</button>
      </template>
      <button type="button" class="text-button" @click="clear">放弃本次预览</button>
    </template>
  </section>
</template>
