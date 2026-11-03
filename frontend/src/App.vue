<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute } from 'vue-router'
import AppIcon from './components/AppIcon.vue'
import { useHistoryStore } from './stores/history'
import { useSystemStore } from './stores/system'
const route = useRoute(), history = useHistoryStore(), system = useSystemStore()
const menuOpen = ref(false), compactNav = ref(matchMedia('(max-width: 1050px)').matches)
const media = matchMedia('(max-width: 1050px)')
function resize() { compactNav.value = media.matches; if (!media.matches) menuOpen.value = false }
media.addEventListener('change', resize)
watch(menuOpen, async open => { document.body.style.overflow = open ? 'hidden' : ''; if (open) { await nextTick(); document.querySelector<HTMLAnchorElement>('.primary-nav a')?.focus() } })
onUnmounted(() => { media.removeEventListener('change', resize); document.body.style.overflow = '' })
watch(() => route.fullPath, () => { menuOpen.value = false })
onMounted(() => { void system.refresh() })
</script>
<template>
  <a class="skip-link" href="#main-content">跳到主要内容</a>
  <div class="app-shell">
    <header class="mobile-header"><RouterLink to="/" class="brand"><AppIcon name="branch" :size="24" />RepoPilot</RouterLink><button class="icon-button" aria-label="打开导航" :aria-expanded="menuOpen" @click="menuOpen = !menuOpen"><AppIcon :name="menuOpen ? 'close' : 'menu'" /></button></header>
    <button v-if="menuOpen" class="nav-backdrop" aria-label="关闭导航" @click="menuOpen = false" />
    <aside class="sidebar" :class="{ 'is-open': menuOpen }" aria-label="工作区导航" :inert="compactNav && !menuOpen" @keydown.esc="menuOpen = false">
      <RouterLink to="/" class="brand desktop-brand"><AppIcon name="branch" :size="26" />RepoPilot</RouterLink>
      <div class="workspace-label">代码运维分析</div>
      <nav class="primary-nav" aria-label="主导航"><RouterLink to="/" :class="{ active: route.path === '/' }"><AppIcon name="chat" />分析工作台</RouterLink><RouterLink to="/repositories" active-class="active"><AppIcon name="folder" />仓库管理</RouterLink></nav>
      <section class="recent-runs" aria-label="最近分析"><h2>最近分析</h2><p v-if="!history.entries.length" class="sidebar-hint">完成一次分析后，可在这里重新查看。</p><RouterLink v-for="entry in history.entries" :key="entry.id" :to="{ path: '/', query: { run: entry.id } }" :class="{ 'current-run': route.query.run === entry.id }"><AppIcon :name="entry.task === 'INCIDENT_FIX' ? 'search' : 'branch'" :size="15" /><span>{{ entry.task === 'INCIDENT_FIX' ? '历史修复分析' : '依赖影响分析' }}</span><code>{{ entry.id.slice(0, 4) }}</code></RouterLink></section>
      <nav class="utility-nav" aria-label="帮助与服务"><RouterLink to="/scope" active-class="active"><AppIcon name="book" />使用指南</RouterLink><RouterLink to="/overview" active-class="active"><AppIcon name="activity" />服务状态</RouterLink></nav>
      <div class="sidebar-status"><span class="status-dot" :class="{ 'is-online': !!system.capabilities }" /><span>{{ system.capabilities ? '本地服务已连接' : system.loading ? '正在连接本地服务' : '本地服务未连接' }}</span></div>
    </aside>
    <div class="app-workspace" :inert="compactNav && menuOpen"><div class="workspace-bar"><span>工作区 <AppIcon name="chevron" :size="13" /><strong>{{ route.path === '/repositories' ? '仓库管理' : route.path === '/overview' ? '服务状态' : route.path === '/scope' ? '使用指南' : '分析工作台' }}</strong></span><span class="read-only"><AppIcon name="shield" :size="15" />只读分析</span></div><main id="main-content" class="workspace-sheet"><RouterView /></main></div>
  </div>
</template>
