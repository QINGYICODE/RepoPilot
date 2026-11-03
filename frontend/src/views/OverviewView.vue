<script setup lang="ts">
import { onMounted } from 'vue'
import { RouterLink } from 'vue-router'
import { useSystemStore } from '../stores/system'

const system = useSystemStore()
onMounted(() => { void system.refresh() })
</script>

<template>
  <header class="page-header"><span class="eyebrow">WORKSPACE / OVERVIEW</span><span class="phase-tag">仓库证据 · 只读分析</span></header>
  <section class="hero">
    <p class="eyebrow">从代码证据，找到排查起点</p>
    <h1>让每一个结论，<br />都有据可查。</h1>
    <p class="hero-description">聚焦依赖变更和历史修复，把分散的代码、配置与提交记录，组织成可复核的运维线索。</p>
    <RouterLink class="text-link" to="/scope">了解分析边界 <span aria-hidden="true">↗</span></RouterLink>
    <div class="hero-mark" aria-hidden="true"><span></span><span></span><span></span></div>
  </section>
  <section class="status-section" aria-labelledby="status-title">
    <div class="section-title"><h2 id="status-title">服务连接</h2><button :disabled="system.loading" @click="system.refresh">{{ system.loading ? '检查中…' : '刷新状态' }}</button></div>
    <div v-if="system.error" class="notice error" role="alert"><strong>暂时无法连接后端</strong><p>{{ system.error }}。请确认后端和 MySQL 已启动，再刷新状态。</p></div>
    <div v-else-if="!system.capabilities" class="notice" role="status">正在读取实际服务状态…</div>
    <div v-else class="status-grid" aria-live="polite">
      <article class="status-card"><p>后端与数据库</p><strong><span class="dot"></span> 已连接</strong><small>最近检查 {{ system.checkedAt }}</small></article>
      <article class="status-card"><p>已登记仓库</p><strong class="number">{{ system.capabilities.repositoryCount }} <span>个</span></strong><small>可在仓库分析页导入固定快照</small></article>
      <article class="status-card"><p>模型连接</p><strong>{{ system.capabilities.liveModelEnabled ? '已启用' : '未启用' }}</strong><small>{{ system.capabilities.liveModelEnabled ? '启用不代表真实调用已验证' : '可先完成基础服务验证' }}</small></article>
    </div>
  </section>
  <section class="section-title roadmap-title"><h2>分析场景</h2><span>每次分析绑定明确的仓库版本</span></section>
  <div class="feature-grid">
    <article class="feature-card"><span class="feature-number">01 / CHANGE IMPACT</span><h3>依赖变更影响</h3><p>选择比较版本并提出问题，Agent 调用依赖分析工具，返回服务影响与证据路径。</p><RouterLink class="text-link" to="/">开始分析 ↗</RouterLink></article>
    <article class="feature-card"><span class="feature-number">02 / HISTORICAL FIX</span><h3>告警历史修复</h3><p>提交异常和部署版本，检索真实历史 diff 支持的修复候选，明确区分相似线索与确定关联。</p><RouterLink class="text-link" to="/">排查告警 ↗</RouterLink></article>
  </div>
  <footer class="page-footer"><span>声明依赖范围不等同于运行时影响；历史相似记录不等同于已确认修复。</span><span v-if="system.capabilities">Spring Boot {{ system.capabilities.versions.springBoot }} · Spring AI {{ system.capabilities.versions.springAi }}</span></footer>
</template>
