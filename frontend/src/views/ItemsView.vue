<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import type { Page } from '../lib/identity'
import { itemStatuses, type ItemSummary } from '../lib/items'
import PageNavigation from '../components/PageNavigation.vue'
import PrivateImage from '../components/PrivateImage.vue'
const route = useRoute()
const auth = useAuthStore()
const admin = computed(() => route.path.startsWith('/admin/'))
const mine = computed(() => route.path === '/items/mine')
const heading = computed(() => admin.value ? '物品内容审核' : mine.value ? '我的发布' : '物品大厅')
const keyword = ref(''), type = ref(''), category = ref(''), status = ref(''), itemId = ref('')
const result = ref<Page<ItemSummary> | null>(null)
const loading = ref(false), error = ref('')
let sequence = 0
let controller: AbortController | undefined
async function load(page = 1) {
  const current = ++sequence; controller?.abort(); controller = new AbortController()
  result.value = null; loading.value = true; error.value = ''
  const query = new URLSearchParams({ page: String(page), pageSize: '10', keyword: keyword.value.trim(), type: type.value, category: category.value.trim() })
  if (admin.value || mine.value) query.set('status', status.value)
  if (admin.value && itemId.value) query.set('itemId', itemId.value)
  const path = admin.value ? '/api/admin/items' : mine.value ? '/api/items/mine/page' : '/api/items/page'
  try { const data = await auth.client<Page<ItemSummary>>(`${path}?${query}`, { signal: controller.signal }); if (current === sequence) result.value = data }
  catch (failure) { if (current === sequence) error.value = failure instanceof Error ? failure.message : '读取失败' }
  finally { if (current === sequence) loading.value = false }
}
watch(() => route.path, () => { keyword.value = ''; type.value = ''; category.value = ''; status.value = admin.value ? 'PENDING' : ''; itemId.value = ''; void load() }, { immediate: true })
onBeforeUnmount(() => { ++sequence; controller?.abort(); result.value = null })
</script>
<template>
  <section class="items-page">
    <div class="page-heading"><div><span class="eyebrow">CAMPUS LOST &amp; FOUND</span><h1>{{ heading }}</h1><p class="muted">{{ admin ? '核对当前内容版本，审核决定会记录在操作历史中。' : '多一份留意，让失物早一点回家。' }}</p></div><RouterLink v-if="auth.canUseBusiness && !admin" class="primary-button" to="/items/new">发布启事</RouterLink></div>
    <form class="search-panel item-filters" @submit.prevent="load(1)">
      <label>关键词<input v-model="keyword" type="search" maxlength="100" placeholder="物品名称或特征" /></label>
      <label>类型<select v-model="type"><option value="">全部类型</option><option value="LOST">寻物启事</option><option value="FOUND">招领启事</option></select></label>
      <label>分类<input v-model="category" maxlength="40" placeholder="如：生活用品" /></label>
      <label v-if="admin || mine">状态<select v-model="status"><option value="">全部状态</option><option v-for="(label, key) in itemStatuses" :key="key" :value="key">{{ label }}</option></select></label>
      <label v-if="admin">物品ID<input v-model="itemId" type="number" min="1" step="1" /></label>
      <button class="primary-button" type="submit" :disabled="loading">查询</button>
    </form>
    <p v-if="loading" class="state-panel" role="status">正在读取最新物品…</p>
    <div v-else-if="error" class="state-panel" role="alert"><p>{{ error }}</p><button class="secondary-button" @click="load()">重新查询</button></div>
    <template v-else-if="result">
      <div v-if="!result.records.length" class="state-panel">没有符合筛选条件的物品。</div>
      <div v-else class="items-grid">
        <article v-for="item in result.records" :key="item.id" class="item-card">
          <PrivateImage v-if="item.images[0]" :id="item.images[0].id" :alt="item.title" />
          <div class="item-card-top"><span :class="['item-type', item.type === 'FOUND' ? 'found' : 'lost']">{{ item.type === 'FOUND' ? '招领启事' : '寻物启事' }}</span><span>{{ itemStatuses[item.status] }}</span></div>
          <h2><RouterLink :to="`${admin ? '/admin/items' : '/items'}/${item.id}`">{{ item.title }}</RouterLink></h2>
          <p class="muted">{{ item.category || '未分类' }} · {{ item.publisherNickname }}</p>
          <div class="item-metadata"><span>{{ item.location || '地点未填写' }}</span><span>{{ item.occurredAt || '日期未填写' }}</span></div>
          <p class="item-footnote">物品 #{{ item.id }} · 内容第 {{ item.contentVersion }} 版</p>
        </article>
      </div>
      <PageNavigation :page="result.page" :page-size="result.pageSize" :total="result.total" :busy="loading" @change="load" />
    </template>
  </section>
</template>
