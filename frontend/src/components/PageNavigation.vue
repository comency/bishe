<script setup lang="ts">
defineProps<{ page: number; pageSize: number; total: number; busy?: boolean }>()
defineEmits<{ change: [page: number] }>()
</script>

<template>
  <nav class="pagination" aria-label="分页">
    <span>共 {{ total }} 条 · 第 {{ page }} / {{ Math.max(1, Math.ceil(total / pageSize)) }} 页</span>
    <div class="button-row">
      <button class="secondary-button" :disabled="busy || page <= 1" @click="$emit('change', page - 1)">上一页</button>
      <button class="secondary-button" :disabled="busy || page * pageSize >= total" @click="$emit('change', page + 1)">下一页</button>
    </div>
  </nav>
</template>
