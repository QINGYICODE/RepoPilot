<script setup lang="ts">
import { onMounted } from 'vue'
import { RouterLink } from 'vue-router'
import { useSystemStore } from '../stores/system'

const system = useSystemStore()
onMounted(() => { void system.refresh() })
</script>

<template>
  <header class="page-header"><span class="eyebrow">WORKSPACE / OVERVIEW</span><span class="phase-tag">D1 · 技术基线</span></header>
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
      <article class="status-card"><p>已登记仓库</p><strong class="number">{{ system.capabilities.repositoryCount }} <span>个</span></strong><small>仓库同步计划在 D2 实施</small></article>
      <article class="status-card"><p>模型连接</p><strong>{{ system.capabilities.liveModelEnabled ? '已启用' : '未启用' }}</strong><small>{{ system.capabilities.liveModelEnabled ? '启用不代表真实调用已验证' : '可先完成基础服务验证' }}</small></article>
    </div>
  </section>
  <section class="section-title roadmap-title"><h2>能力建设</h2><span>按业务闭环逐步交付</span></section>
  <div class="feature-grid">
    <article class="feature-card"><span class="feature-number">01 / CHANGE IMPACT</span><h3>依赖变更影响</h3><p>比较指定版本的依赖变化，追踪使用方与潜在调用影响，逐条展示依赖路径。</p><span class="planned-tag">计划中 · D4</span></article>
    <article class="feature-card"><span class="feature-number">02 / FIX HISTORY</span><h3>历史修复检索</h3><p>从告警和异常定位相关提交，核对真实 diff，区分相似修复与确定关联。</p><span class="planned-tag">计划中 · D5</span></article>
  </div>
  <footer class="page-footer"><span>当前已交付：基础服务、数据库迁移与只读工具探针。</span><span v-if="system.capabilities">Spring Boot {{ system.capabilities.versions.springBoot }} · Spring AI {{ system.capabilities.versions.springAi }}</span></footer>
</template>
