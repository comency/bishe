<script setup lang="ts">
import { RouterLink } from 'vue-router'
</script>

<template>
  <section class="state-panel not-found"><span class="eyebrow">404 · PAGE NOT FOUND</span><h1>这个页面还没找到</h1><p>地址可能有误，或相关功能尚未开放。</p><RouterLink to="/items" class="primary-button">返回物品大厅</RouterLink></section>
</template>
