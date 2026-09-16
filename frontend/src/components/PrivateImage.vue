<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { useAuthStore } from '../stores/auth'
const props = defineProps<{ id: number; alt?: string }>()
const auth = useAuthStore()
const url = ref('')
const error = ref('')
let controller: AbortController | undefined
let sequence = 0
function release() { ++sequence; controller?.abort(); if (url.value) URL.revokeObjectURL(url.value); url.value = '' }
watch(() => [props.id, auth.revision], async () => {
  release(); error.value = ''; const current = sequence
  controller = new AbortController()
  try {
    const blob = await auth.client<Blob>(`/api/uploads/images/${props.id}`, { responseType: 'blob', signal: controller.signal })
    if (current === sequence) url.value = URL.createObjectURL(blob)
  } catch (failure) { if (current === sequence) error.value = failure instanceof Error ? failure.message : '图片读取失败' }
}, { immediate: true })
onBeforeUnmount(release)
</script>
<template>
  <img v-if="url" class="private-image" :src="url" :alt="alt || '物品图片'" />
  <span v-else class="image-placeholder" role="status">{{ error || '正在读取图片…' }}</span>
</template>
