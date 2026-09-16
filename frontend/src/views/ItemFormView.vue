<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useConfigStore } from '../stores/config'
import { campusToday } from '../lib/identity'
import { ApiError } from '../lib/request'
import type { ImageMeta, ItemDetail } from '../lib/items'
import PrivateImage from '../components/PrivateImage.vue'
import AiPolishPreview from '../components/AiPolishPreview.vue'
const route = useRoute(), router = useRouter(), auth = useAuthStore(), config = useConfigStore()
const editing = computed(() => !!route.params.id)
const form = reactive({ title: '', description: '', type: 'LOST', category: '', location: '', occurredAt: '' })
const images = ref<ImageMeta[]>([]), version = ref<number | null>(null)
const loading = ref(false), saving = ref(false), uploading = ref(false), error = ref(''), blocked = ref(false)
const today = computed(() => campusToday(config.config?.timezone))
let sequence = 0
let controller = new AbortController()
watch(() => route.fullPath, async () => {
  const current = ++sequence; controller.abort(); controller = new AbortController(); blocked.value = false; error.value = ''; images.value = []; version.value = null; loading.value = false; uploading.value = false; saving.value = false
  Object.assign(form, { title: '', description: '', type: 'LOST', category: '', location: '', occurredAt: '' })
  if (!editing.value) return
  loading.value = true
  try {
    const item = await auth.client<ItemDetail>(`/api/items/${String(route.params.id)}`, { signal: controller.signal })
    if (current !== sequence) return
    if (item.publisherId !== auth.session?.userId || item.status === 'CLOSED') { blocked.value = true; throw new Error('只有本人未关闭的发布可以编辑。') }
    Object.assign(form, { title: item.title, description: item.description, type: item.type, category: item.category || '', location: item.location || '', occurredAt: item.occurredAt || '' })
    images.value = item.images; version.value = item.version
  } catch (failure) { if (current === sequence) { blocked.value = true; error.value = failure instanceof Error ? failure.message : '读取失败' } }
  finally { if (current === sequence) loading.value = false }
}, { immediate: true })
async function upload(event: Event) {
  const input = event.target as HTMLInputElement, file = input.files?.[0]; input.value = ''
  if (!file || uploading.value || saving.value || images.value.length >= 3) return
  if (!['image/jpeg', 'image/png'].includes(file.type) || file.size > 5 * 1024 * 1024) { error.value = '请选择不超过5MiB的JPEG或PNG图片。'; return }
  uploading.value = true; error.value = ''; const current = sequence
  try { const data = new FormData(); data.append('file', file); const image = await auth.client<ImageMeta>('/api/uploads/images', { method: 'POST', body: data, signal: controller.signal, timeoutMs: 30000 }); if (current === sequence) images.value.push(image) }
  catch (failure) { if (current === sequence) error.value = failure instanceof Error ? failure.message : '上传失败' }
  finally { if (current === sequence) uploading.value = false }
}
async function submit() {
  if (saving.value || uploading.value || blocked.value || loading.value) return
  saving.value = true; error.value = ''; const current = sequence
  try {
    const body = { ...form, title: form.title.trim(), description: form.description.trim(), category: form.category.trim() || null, location: form.location.trim() || null, occurredAt: form.occurredAt || null, imageIds: images.value.map(i => i.id), ...(editing.value ? { expectedVersion: version.value } : {}) }
    const item = await auth.client<ItemDetail>(editing.value ? `/api/items/${String(route.params.id)}` : '/api/items', { method: editing.value ? 'PUT' : 'POST', body, signal: controller.signal })
    if (current === sequence) await router.push(`/items/${item.id}`)
  } catch (failure) {
    if (current === sequence) { error.value = failure instanceof Error ? failure.message : '提交失败'; blocked.value = !(failure instanceof ApiError && failure.status === 400) }
  } finally { if (current === sequence) saving.value = false }
}
onBeforeUnmount(() => { ++sequence; controller.abort(); images.value = [] })
</script>
<template>
  <section class="item-editor">
    <div class="page-heading"><div><span class="eyebrow">SHARE A CLUE</span><h1>{{ editing ? '编辑启事' : '发布启事' }}</h1><p class="muted">保存后进入人工内容审核，审核通过才会出现在大厅。</p></div><RouterLink to="/items/mine" class="secondary-button">我的发布</RouterLink></div>
    <p class="environment-notice">请勿在正文或图片公开姓名、学号、联系方式等个人信息。认领说明和交接联系方式请通过独立认领流程提供。</p>
    <p v-if="error" class="error-message" role="alert">{{ error }}</p>
    <p v-if="blocked">请先到“我的发布”查询最新记录，确认结果后再编辑。系统不会自动重试提交。</p>
    <p v-if="loading" role="status">正在读取…</p>
    <form v-else class="form-card item-form" @submit.prevent="submit">
      <fieldset :disabled="saving || blocked || uploading">
        <label>信息类型<select v-model="form.type"><option value="LOST">寻物启事</option><option value="FOUND">招领启事</option></select></label>
        <label>标题<input v-model="form.title" required maxlength="100" placeholder="简要描述物品和主要特征" /></label>
        <label>详细描述<textarea v-model="form.description" required maxlength="3000" rows="6" /></label>
        <AiPolishPreview :text="form.description" :disabled="saving || blocked || uploading" @apply="form.description = $event" />
        <div class="item-form-row"><label>分类<input v-model="form.category" maxlength="40" list="item-categories" /></label><label>地点<input v-model="form.location" maxlength="100" /></label><label>发生日期<input v-model="form.occurredAt" type="date" :max="today" /></label></div>
        <datalist id="item-categories"><option v-for="category in config.config?.categories" :key="category" :value="category" /></datalist>
        <label>物品图片（最多三张，每张5MiB）<input type="file" accept="image/jpeg,image/png" :disabled="images.length >= 3" @change="upload" /></label>
        <div class="image-gallery"><div v-for="(image, index) in images" :key="image.id"><PrivateImage :id="image.id" /><button type="button" class="text-button" @click="images.splice(index, 1)">移除第 {{ index + 1 }} 张</button></div></div>
        <button type="submit" class="primary-button">{{ saving ? '提交中…' : '保存并提交审核' }}</button>
      </fieldset>
      <p v-if="uploading" role="status">正在校验并上传图片…</p>
    </form>
  </section>
</template>
