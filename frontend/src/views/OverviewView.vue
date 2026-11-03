<script setup lang="ts">
import { onMounted } from 'vue'
import { RouterLink } from 'vue-router'
import AppIcon from '../components/AppIcon.vue'
import { useSystemStore } from '../stores/system'
const system = useSystemStore()
onMounted(() => { void system.refresh() })
</script>
<template>
  <header class="page-heading"><div><h1>服务状态</h1><p>检查分析所需的本机服务是否就绪。</p></div><button class="button secondary" :disabled="system.loading" @click="system.refresh"><AppIcon name="refresh" :size="16" />{{ system.loading ? '正在检查' : '重新检查' }}</button></header>
  <div v-if="system.error" class="inline-error" role="alert"><AppIcon name="warning" /><div><strong>暂时无法连接后端</strong><p>{{ system.error }}。请确认后端和本机 MySQL 已启动，再重新检查。</p></div></div>
  <p v-else-if="!system.capabilities" role="status" class="muted">正在读取服务状态…</p>
  <template v-else>
    <div class="service-status-list"><div class="service-status-row"><AppIcon name="activity" /><div><h3>后端与数据库</h3><p>连接到本机 MySQL，读取真实仓库和分析记录。</p></div><span class="status-label"><span class="status-dot is-online" />已连接</span></div><div class="service-status-row"><AppIcon name="chat" /><div><h3>分析模型</h3><p>{{ system.capabilities.liveModelEnabled ? '已启用模型调用，具体任务的执行结果可在分析记录中核对。' : '尚未启用。配置模型后，请使用 python3 scripts/dev.py agent 启动后端。' }}</p></div><span class="status-label"><span class="status-dot" :class="{ 'is-online': system.capabilities.liveModelEnabled }" />{{ system.capabilities.liveModelEnabled ? '已启用' : '未启用' }}</span></div><div class="service-status-row"><AppIcon name="folder" /><div><h3>已接入仓库</h3><p>{{ system.capabilities.repositoryCount }} 个仓库已登记；可用版本请在仓库管理中查看。</p></div><RouterLink class="text-link" to="/repositories">管理仓库<AppIcon name="chevron" :size="14" /></RouterLink></div></div>
    <p class="field-help" style="margin-top: 20px">最近检查：{{ system.checkedAt }}</p>
    <details class="disclosure"><summary>运行环境版本</summary><dl class="execution-facts"><div v-for="(version, name) in system.capabilities.versions" :key="name"><dt>{{ name }}</dt><dd><code>{{ version }}</code></dd></div></dl></details>
  </template>
</template>
