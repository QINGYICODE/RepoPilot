<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { request, post, type Repository, type Snapshot, type SyncJob, type Evidence, type SearchPage, type ImpactResult } from '../api/repository'

const repositories = ref<Repository[]>([])
const selectedRepo = ref<number>()
const snapshots = ref<Snapshot[]>([])
const snapshotId = ref<number>()
const baseId = ref<number>()
const headId = ref<number>()
const fullName = ref('spring-petclinic/spring-petclinic-microservices')
const commitSha = ref('3858f9c630cf989bb6809a86edf47c2be78dc9f1')
const job = ref<SyncJob | null>(null)
const error = ref('')
const busy = ref(false)
const query = ref('zipkin')
const sourceType = ref('')
const page = ref<SearchPage | null>(null)
const evidence = ref<Evidence | null>(null)
const result = ref<ImpactResult | null>(null)
const modulePath = ref('')
const selectedSnapshot = computed(() => snapshots.value.find(s => s.id === snapshotId.value))
let timer: ReturnType<typeof setTimeout> | undefined
let disposed = false

async function run(action: () => Promise<void>) {
  if (busy.value) return
  busy.value = true; error.value = ''
  try { await action() } catch (cause) { error.value = cause instanceof Error ? cause.message : '操作失败，请重试' }
  finally { busy.value = false }
}
async function loadSnapshots() {
  page.value = null; evidence.value = null; result.value = null
  if (!selectedRepo.value) { snapshots.value = []; return }
  const repo = repositories.value.find(r => r.id === selectedRepo.value)
  snapshots.value = await request<Snapshot[]>(`/repositories/${selectedRepo.value}/snapshots`)
  snapshotId.value = repo?.activeSnapshotId ?? snapshots.value[0]?.id
  headId.value = snapshotId.value
  baseId.value = snapshots.value.find(s => s.id !== headId.value)?.id ?? snapshotId.value
}
async function loadRepositories() {
  repositories.value = await request<Repository[]>('/repositories')
  if (!selectedRepo.value) selectedRepo.value = repositories.value[0]?.id
  await loadSnapshots()
}
async function register() {
  await run(async () => {
    const parts = fullName.value.trim().split('/')
    if (parts.length !== 2) throw new Error('请输入 owner/repository 格式的仓库名称')
    const repo = await post<Repository>('/repositories', { owner: parts[0], name: parts[1] })
    selectedRepo.value = repo.id; await loadRepositories()
  })
}
async function poll(id: string, repoId: number) {
  try {
    const current = await request<SyncJob>(`/sync-jobs/${id}`)
    if (disposed || job.value?.id !== id) return
    job.value = current
    if (current.status === 'READY') {
      if (selectedRepo.value === repoId) {
        await loadRepositories()
        if (current.snapshotId) { snapshotId.value = current.snapshotId; headId.value = current.snapshotId }
      }
    } else if (current.status === 'FAILED') error.value = `同步失败：${current.errorCode ?? '未知错误'}。可使用相同 SHA 重试。`
    else timer = setTimeout(() => { void poll(id, repoId) }, 1500)
  } catch (cause) { if (!disposed) error.value = `进度获取失败：${cause instanceof Error ? cause.message : '网络异常'}，可刷新工作台重新查询。` }
}
async function synchronize() {
  await run(async () => {
    if (!selectedRepo.value) throw new Error('请先登记并选择仓库')
    if (!/^[a-f0-9]{40}$/i.test(commitSha.value.trim())) throw new Error('请输入完整的 40 位 commit SHA')
    if (timer) clearTimeout(timer)
    job.value = await post<SyncJob>(`/repositories/${selectedRepo.value}/sync-jobs`, { commitSha: commitSha.value.trim() })
    void poll(job.value.id, selectedRepo.value)
  })
}
async function search(cursor?: string) {
  await run(async () => {
    if (!selectedRepo.value || !snapshotId.value) throw new Error('请选择已就绪的快照')
    const params = new URLSearchParams({ q: query.value, limit: '20' })
    if (sourceType.value) params.set('type', sourceType.value)
    if (cursor) params.set('cursor', cursor)
    page.value = await request<SearchPage>(`/repositories/${selectedRepo.value}/snapshots/${snapshotId.value}/search?${params}`)
  })
}
async function compare() {
  await run(async () => {
    if (!selectedRepo.value || !baseId.value || !headId.value) throw new Error('请选择 base 和 head 快照')
    result.value = await post<ImpactResult>(`/repositories/${selectedRepo.value}/dependency-impact`, { baseSnapshotId: baseId.value, headSnapshotId: headId.value, module: modulePath.value.trim() || null })
  })
}
async function showEvidence(id: string, view: 'BASE' | 'HEAD') {
  await run(async () => { evidence.value = await request<Evidence>(`/repositories/${selectedRepo.value}/snapshots/${view === 'BASE' ? result.value?.baseSnapshotId : result.value?.headSnapshotId}/evidence/${id}`) })
}
onMounted(() => { void run(loadRepositories) })
onUnmounted(() => { disposed = true; if (timer) clearTimeout(timer) })
</script>

<template>
  <header class="page-header"><span class="eyebrow">WORKSPACE / REPOSITORY</span><span class="phase-tag">固定版本 · 可追溯证据</span></header>
  <h1 class="repo-title">仓库分析</h1>
  <p class="hero-description">登记允许访问的仓库，导入指定提交，在固定快照中检索证据、比较依赖变化。</p>
  <div v-if="error" class="notice error" role="alert">{{ error }}</div>
  <section class="repo-panel">
    <h2>仓库与快照</h2>
    <form class="repo-form" @submit.prevent="register"><label>GitHub 仓库<input v-model="fullName" required aria-label="GitHub 仓库" /></label><button :disabled="busy">登记仓库</button></form>
    <div class="repo-form"><label>已登记仓库<select v-model="selectedRepo" @change="run(loadSnapshots)"><option v-for="repo in repositories" :key="repo.id" :value="repo.id">{{ repo.owner }}/{{ repo.name }}</option></select></label><button :disabled="busy" @click="run(loadRepositories)">刷新</button></div>
    <form class="repo-form" @submit.prevent="synchronize"><label>完整 commit SHA<input v-model="commitSha" required minlength="40" maxlength="40" /></label><button :disabled="busy || !selectedRepo">同步并建立索引</button></form>
    <p v-if="job" role="status" class="repo-note">同步状态：{{ job.status }} · {{ job.targetSha.slice(0, 12) }}</p>
    <label class="repo-select">检索快照<select v-model="snapshotId" @change="page = null; evidence = null"><option v-for="s in snapshots" :key="s.id" :value="s.id">{{ s.commitSha.slice(0, 12) }} · {{ s.stats.fileCount }} 个文件 · {{ s.stats.commitCount }} 个提交</option></select></label>
    <details v-if="selectedSnapshot" class="repo-note"><summary>索引覆盖范围：{{ selectedSnapshot.coverage.level }}；历史{{ selectedSnapshot.coverage.historyComplete ? '已完整遍历' : '覆盖不完整' }}</summary><ul><li v-for="warning in selectedSnapshot.coverage.warnings" :key="warning">{{ warning }}</li></ul></details>
  </section>
  <section class="repo-panel">
    <h2>代码与历史证据检索</h2>
    <form class="repo-form" @submit.prevent="search()"><label>异常、符号、路径或 SHA<input v-model="query" required maxlength="200" /></label><select v-model="sourceType" aria-label="证据类型"><option value="">全部证据</option><option value="FILE">当前代码</option><option value="COMMIT">提交记录</option><option value="DIFF">历史 diff</option></select><button :disabled="busy || !snapshotId">检索</button></form>
    <p v-if="page" class="repo-note">本页 {{ page.items.length }} 条 · {{ page.retrievalMethod }}<span v-if="!page.items.length"> · 当前查询范围内没有命中</span></p>
    <article v-for="item in page?.items" :key="item.id" class="evidence-card"><div><span class="phase-tag">{{ item.type }}</span> <a :href="item.sourceUrl" target="_blank" rel="noreferrer">{{ item.path || item.sourceSha.slice(0, 12) }} ↗</a></div><p class="repo-note">{{ item.symbol }} · {{ item.sourceSha.slice(0, 12) }} · 行 {{ item.startLine }}–{{ item.endLine }}<span v-if="item.truncated"> · 片段已截断</span></p><pre>{{ item.excerpt }}</pre><details v-if="item.type === 'DIFF'"><summary>diff 两侧定位</summary><pre>{{ JSON.stringify(item.locator, null, 2) }}</pre></details></article>
    <button v-if="page?.nextCursor" :disabled="busy" @click="search(page.nextCursor)">下一页</button>
  </section>
  <section class="repo-panel">
    <h2>依赖变更影响</h2><p class="repo-note">分析 Maven 声明依赖及其反向使用方；测试、provided、optional 范围单独标记。结果不代表运行时故障判定。</p>
    <form class="repo-form" @submit.prevent="compare"><label>Base<select v-model="baseId"><option v-for="s in snapshots" :key="s.id" :value="s.id">{{ s.commitSha.slice(0, 12) }}</option></select></label><label>Head<select v-model="headId"><option v-for="s in snapshots" :key="s.id" :value="s.id">{{ s.commitSha.slice(0, 12) }}</option></select></label><label>模块路径（可选）<input v-model="modulePath" placeholder="留空分析全部模块" /></label><button :disabled="busy || !baseId || !headId">比较依赖</button></form>
    <template v-if="result"><p>声明影响涉及的服务：{{ result.affectedServices.join('、') || '未发现已确认的声明影响' }}</p><div class="table-scroll"><table><thead><tr><th>模块</th><th>依赖</th><th>变化</th><th>Base</th><th>Head</th></tr></thead><tbody><tr v-for="change in result.changes" :key="change.id"><td>{{ change.module }}</td><td>{{ change.coordinate }}</td><td>{{ change.kind }}</td><td>{{ change.before?.version || '—' }}</td><td>{{ change.after?.version || '—' }}</td></tr></tbody></table></div><article v-for="(item, i) in result.impacts" :key="i" class="evidence-card"><strong>{{ item.service }}</strong><p>{{ item.path.join(' → ') }}</p><p class="repo-note">{{ item.view }} · {{ item.scope }} · {{ item.kind }}</p><button v-for="(id, n) in item.evidenceIds" :key="id" :disabled="busy" @click="showEvidence(id, item.view)">依据 {{ n + 1 }}</button></article><details><summary>分析限制与未知项（{{ result.warnings.length }}）</summary><ul><li v-for="warning in result.warnings" :key="warning">{{ warning }}</li></ul></details><p v-if="result.truncated" role="status">路径结果已达到数量或深度限制。</p></template>
    <article v-if="evidence" class="evidence-card"><h3>已核验证据</h3><a :href="evidence.sourceUrl" target="_blank" rel="noreferrer">{{ evidence.path }} · {{ evidence.sourceSha.slice(0, 12) }} · 行 {{ evidence.startLine }}–{{ evidence.endLine }} ↗</a><pre>{{ evidence.excerpt }}</pre></article>
  </section>
</template>
