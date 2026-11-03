<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router'
</script>

<template>
  <div class="shell">
    <aside class="sidebar">
      <RouterLink to="/" class="brand"><span class="brand-icon">R</span>RepoPilot</RouterLink>
      <p class="sidebar-caption">代码运维分析 Agent</p>
      <nav aria-label="主导航">
        <RouterLink to="/" exact-active-class="active"><span>01</span> 提问与分析</RouterLink>
        <RouterLink to="/repositories" active-class="active"><span>02</span> 仓库与证据</RouterLink>
        <RouterLink to="/overview" active-class="active"><span>03</span> 服务状态</RouterLink>
        <RouterLink to="/scope" active-class="active"><span>04</span> 分析范围</RouterLink>
      </nav>
      <div class="sidebar-footer"><span class="dot"></span> 本地开发环境<br /><small>基于仓库证据的运维分析</small></div>
    </aside>
    <main><RouterView /></main>
  </div>
</template>
