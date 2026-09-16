<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { searchItems, type ItemSummary } from '../api'

const keyword = ref('')
const type = ref('')
const items = ref<ItemSummary[]>([])
const loading = ref(false)
const error = ref('')
let requestNumber = 0
let controller: AbortController | undefined

async function load() {
  const current = ++requestNumber
  controller?.abort()
  controller = new AbortController()
  loading.value = true
  error.value = ''
  items.value = []
  try {
    const result = await searchItems(keyword.value.trim(), type.value, controller.signal)
    if (current === requestNumber) items.value = result
  } catch (failure) {
    if (current === requestNumber) error.value = failure instanceof Error ? failure.message : '加载失败，请稍后重试。'
  } finally { if (current === requestNumber) loading.value = false }
}

async function reset() { keyword.value = ''; type.value = ''; await load() }
onMounted(load)
onBeforeUnmount(() => { ++requestNumber; controller?.abort(); items.value = [] })
</script>

<template>
  <section class="items-page">
    <div class="page-heading"><div><span class="eyebrow">CAMPUS LOST &amp; FOUND</span><h1>物品大厅</h1><p class="muted">多一份留意，让失物早一点回家。</p></div><span class="outline-badge">本地联调 · 非正式开放</span></div>
    <div class="development-banner"><strong>校园资格有效</strong><span>进入大厅已核实最新资格，每次请求由服务端再次校验。详情、发布与认领交接将在后续阶段接入。</span></div>
    <form class="search-panel" @submit.prevent="load">
      <div class="search-field"><label for="item-keyword">查找物品</label><input id="item-keyword" v-model="keyword" type="search" maxlength="100" placeholder="试试物品名称或描述中的特征" /></div>
      <div class="type-field"><label for="item-type">信息类型</label><select id="item-type" v-model="type"><option value="">全部类型</option><option value="LOST">寻物启事</option><option value="FOUND">招领启事</option></select></div>
      <button class="primary-button search-button" type="submit">查询</button>
    </form>
    <div class="result-heading"><h2>校园物品信息</h2><span v-if="!loading && !error">本次返回 {{ items.length }} 条 · 当前接口未分页</span></div>
    <div v-if="loading" class="state-panel" role="status" aria-live="polite"><span class="spinner" aria-hidden="true"></span><h3>正在查找物品</h3><p>正在从后端读取最新信息，请稍候。</p></div>
    <div v-else-if="error" class="state-panel error-state" role="alert"><span class="state-icon" aria-hidden="true">!</span><h3>暂时无法加载</h3><p>{{ error }}</p><button class="secondary-button" @click="load">重新加载</button></div>
    <div v-else-if="items.length === 0" class="state-panel" role="status"><span class="state-icon" aria-hidden="true">⌕</span><h3>暂时没有找到物品</h3><p>当前没有符合条件的已审核信息，试试调整关键词或类型。</p><button class="secondary-button" @click="reset">清空筛选</button></div>
    <div v-else class="items-grid">
      <article v-for="item in items" :key="item.id" class="item-card">
        <div class="item-card-top"><span :class="['item-type', item.type === 'FOUND' ? 'found' : 'lost']">{{ item.type === 'FOUND' ? '招领启事' : '寻物启事' }}</span><span class="item-category">{{ item.category || '未分类' }}</span></div>
        <h3>{{ item.title }}</h3><p class="item-description">{{ item.description }}</p>
        <div class="item-metadata"><span><i aria-hidden="true">◎</i> {{ item.location || '地点未填写' }}</span><span>{{ item.occurredAt || '日期未填写' }}</span></div>
        <p class="item-footnote">详情与认领功能将在后续阶段开放</p>
      </article>
    </div>
  </section>
</template>
