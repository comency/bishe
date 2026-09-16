<script setup lang="ts">
import { useConfigStore } from '../stores/config'
const settings = useConfigStore()
</script>

<template>
  <aside class="development-note">
    <template v-if="settings.config">
      <strong>{{ settings.config.campusName || '校园名称待配置' }} · 人工核验说明</strong>
      <p class="preserve-lines">{{ settings.config.verificationInstructions }}</p>
      <p>支持渠道：{{ settings.config.supportContact || '请联系本地演示负责人；正式支持渠道待配置' }}</p>
      <p>校园时区：{{ settings.config.timezone }}。注册和自填学号不代表认证通过。</p>
    </template>
    <p v-else-if="settings.loading" role="status">正在读取校园核验说明…</p>
    <template v-else><p role="alert">{{ settings.error || '尚未读取校园配置。' }}</p><button class="text-button" @click="settings.load">重新读取说明</button></template>
  </aside>
</template>
