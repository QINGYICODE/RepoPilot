<script setup lang="ts">
import { RouterLink, useRoute, useRouter } from 'vue-router'
import AppIcon from '../components/AppIcon.vue'
import ConnectionStatus from '../components/ConnectionStatus.vue'
import { EXAMPLES, friendlyError, loadCommitLabels, shortSha, type CommitLabel } from '../lib/presentation'
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { request, post, type Repository, type Snapshot, type SyncJob, type ConnectionJob, type RepositoryAccessSettings, type Evidence, type SearchPage, type ImpactResult } from '../api/repository'

const tab = ref('versions')
const labels = ref<Record<number, CommitLabel>>({})
const repositories = ref<Repository[]>([])
const selectedRepo = ref<number>()
const snapshots = ref<Snapshot[]>([])
const snapshotId = ref<number>()
const baseId = ref<number>()
const headId = ref<number>()
const fullName = ref('')
const autoImport = ref(true), connection = ref<ConnectionJob | null>(null), access = ref<RepositoryAccessSettings | null>(null)
const route = useRoute(), router = useRouter(), lifetime = new AbortController()
let connectionTimer: ReturnType<typeof setTimeout> | undefined, connectionGeneration = 0
const connectionBusy = computed(() => !!connection.value && !['READY', 'FAILED'].includes(connection.value.status))
const mayRevoke = computed(() => { const repo = repositories.value.find(r => r.id === selectedRepo.value); return !!repo && !!access.value && !access.value.seededRepositories.some(name => name.toLowerCase() === `${repo.owner}/${repo.name}`.toLowerCase()) })
const commitSha = ref('')
const selectedDemoRepo = computed(() => repositories.value.some(r => r.id === selectedRepo.value && `${r.owner}/${r.name}`.toLowerCase() === 'spring-petclinic/spring-petclinic-microservices'))
const job = ref<SyncJob | null>(null)
const connectionSyncJob = ref<SyncJob | null>(null)
const error = ref('')
const busy = ref(false)
const query = ref('zipkin')
const sourceType = ref('')
const page = ref<SearchPage | null>(null)
const evidence = ref<Evidence | null>(null)
const result = ref<ImpactResult | null>(null)
const modulePath = ref('')
let timer: ReturnType<typeof setTimeout> | undefined
let disposed = false

async function run(action: () => Promise<void>) {
  if (busy.value) return
  busy.value = true; error.value = ''
  try { await action() } catch (cause) { error.value = friendlyError(cause) }
  finally { busy.value = false }
}
async function loadSnapshots() {
  page.value = null; evidence.value = null; result.value = null
  if (!selectedRepo.value) { snapshots.value = []; return }
  const repo = repositories.value.find(r => r.id === selectedRepo.value)
  snapshots.value = await request<Snapshot[]>(`/repositories/${selectedRepo.value}/snapshots`)
  commitSha.value = snapshots.value[0]?.commitSha ?? ''
  snapshotId.value = snapshots.value.find(s => s.id === repo?.activeSnapshotId)?.id ?? snapshots.value[0]?.id
  const ownerId = selectedRepo.value
  void loadCommitLabels(ownerId, snapshots.value).then(data => { if (selectedRepo.value === ownerId) labels.value = data })
  headId.value = snapshotId.value
  baseId.value = snapshots.value.find(s => s.id !== headId.value)?.id ?? snapshotId.value
}
async function loadRepositories() {
  repositories.value = await request<Repository[]>('/repositories')
  if (!selectedRepo.value) selectedRepo.value = repositories.value[0]?.id
  await loadSnapshots()
}
async function register() {
  if (connectionBusy.value) return
  await run(async () => {
    const created = await post<ConnectionJob>('/repository-connections', { address: fullName.value, authorizeAccess: true, autoImport: autoImport.value })
    await followConnection(created)
  })
}
async function followConnection(created: ConnectionJob) {
  clearTimeout(connectionTimer); clearTimeout(timer); job.value = null; connectionSyncJob.value = null; const generation = ++connectionGeneration
  connection.value = created
  try { localStorage.setItem('repopilot.repository-connection', created.id) } catch { /* Task can still be followed by URL. */ }
  await router.replace({ path: '/repositories', query: { connection: created.id } })
  void pollConnection(created.id, generation)
}
async function pollConnection(id: string, generation: number) {
  try {
    const current = await request<ConnectionJob>(`/repository-connections/${id}`, { signal: lifetime.signal })
    if (disposed || generation !== connectionGeneration) return
    connection.value = current
    if (['READY', 'FAILED'].includes(current.status)) {
      if (current.repoId) { selectedRepo.value = current.repoId; await loadRepositories() }
      if (current.syncJobId && current.repoId) {
        job.value = await request<SyncJob>(`/sync-jobs/${current.syncJobId}`, { signal: lifetime.signal })
        connectionSyncJob.value = job.value
        commitSha.value = job.value.targetSha
        void poll(job.value.id, current.repoId)
      }
    } else connectionTimer = setTimeout(() => { void pollConnection(id, generation) }, 1200)
  } catch (cause) { if (!disposed && generation === connectionGeneration) error.value = `接入进度暂时无法读取。${friendlyError(cause)} 刷新页面可继续查询。` }
}
async function retryConnection() {
  if (!connection.value) return
  await run(async () => { await followConnection(await post<ConnectionJob>(`/repository-connections/${connection.value!.id}/retry`, {})) })
}
async function revoke() {
  if (!selectedRepo.value || !mayRevoke.value) return
  await run(async () => {
    await request(`/repositories/${selectedRepo.value}/authorization`, { method: 'DELETE' })
    selectedRepo.value = undefined; connection.value = null; job.value = null; connectionSyncJob.value = null; clearTimeout(timer); clearTimeout(connectionTimer); ++connectionGeneration
    try { localStorage.removeItem('repopilot.repository-connection') } catch { /* Optional local history. */ }
    await router.replace('/repositories'); await loadRepositories()
  })
}
async function poll(id: string, repoId: number) {
  try {
    const current = await request<SyncJob>(`/sync-jobs/${id}`)
    if (disposed || job.value?.id !== id) return
    job.value = current
    if (connection.value?.syncJobId === current.id) connectionSyncJob.value = current
    if (current.status === 'READY') {
      if (selectedRepo.value === repoId) {
        await loadRepositories()
        if (current.snapshotId) { snapshotId.value = current.snapshotId; headId.value = current.snapshotId }
      }
    } else if (current.status === 'FAILED') error.value = `版本导入未完成。${friendlyError(new Error(current.errorCode ?? 'GIT_FETCH_FAILED'))}`
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
onMounted(() => { void run(async () => {
  access.value = await request<RepositoryAccessSettings>('/repository-connections/settings', { signal: lifetime.signal })
    .catch(cause => { error.value = `接入设置暂时无法读取。${friendlyError(cause)}`; return null })
  await loadRepositories()
  let saved: unknown = route.query.connection
  if (!saved) { try { saved = localStorage.getItem('repopilot.repository-connection') ?? undefined } catch { /* Optional persistence. */ } }
  if (typeof saved === 'string' && /^[a-f0-9-]{36}$/.test(saved)) void pollConnection(saved, ++connectionGeneration)
}) })
onUnmounted(() => { disposed = true; ++connectionGeneration; lifetime.abort(); clearTimeout(timer); clearTimeout(connectionTimer) })
</script>

<template>
  <header class="page-heading"><div><h1>仓库管理</h1><p>连接 GitHub 仓库，导入需要分析的代码版本。</p></div><RouterLink class="button secondary" to="/">返回分析<AppIcon name="arrow" :size="15" /></RouterLink></header>
  <div v-if="error" class="inline-error" role="alert"><AppIcon name="warning" /><span>{{ error }}</span></div>
  <div class="page-tabs" role="tablist" aria-label="仓库工具"><button role="tab" :aria-selected="tab === 'versions'" @click="tab = 'versions'">仓库与版本</button><button role="tab" :aria-selected="tab === 'search'" @click="tab = 'search'">代码检索</button><button role="tab" :aria-selected="tab === 'compare'" @click="tab = 'compare'">手动依赖比较</button></div>
  <ConnectionStatus v-if="connection" :connection="connection" :settings="access" :sync-job="connectionSyncJob" :retrying="busy" @retry="retryConnection" @reread="run(loadRepositories)" />
  <template v-if="tab === 'versions'">
    <section v-if="!repositories.length" class="section-block"><h2>接入第一个仓库</h2><p>输入公开 GitHub 仓库地址。接入后，你授权 Agent 在本机只读分析这个仓库。</p><form class="inline-form" @submit.prevent="register"><label class="field"><span>GitHub 仓库地址</span><input v-model="fullName" required placeholder="https://github.com/owner/repository" /></label><button class="button primary" :disabled="busy || connectionBusy">{{ busy ? '正在提交' : '接入并授权' }}</button></form><label class="checkbox-label"><input v-model="autoImport" type="checkbox" />接入后自动导入默认分支最新版本</label></section>
    <template v-else>
      <div class="repo-summary"><AppIcon name="folder" :size="22" /><div><h2>已连接的仓库</h2><span class="muted">仓库代码与历史均为只读访问</span></div></div>
      <div class="inline-form"><label class="field"><span>选择仓库</span><select v-model="selectedRepo" aria-label="选择仓库" :disabled="busy" @change="run(loadSnapshots)"><option v-for="repo in repositories" :key="repo.id" :value="repo.id">{{ repo.owner }}/{{ repo.name }}</option></select></label><button class="button secondary" :disabled="busy" @click="run(loadRepositories)"><AppIcon name="refresh" :size="15" />刷新</button></div>
      <details class="disclosure"><summary>接入其他仓库</summary><form class="inline-form" @submit.prevent="register"><label class="field"><span>GitHub 仓库地址</span><input v-model="fullName" required /></label><button class="button secondary" :disabled="busy || connectionBusy">接入并授权</button></form><label class="checkbox-label"><input v-model="autoImport" type="checkbox" :disabled="busy || connectionBusy" />接入后自动导入默认分支最新版本</label><p class="field-help">你的接入操作授予该公开仓库只读分析权限。Agent 无法自行接入其他仓库。</p><p v-if="access && !access.manualAuthorizationEnabled" class="field-help">当前维护者关闭了新增授权，仅能接入已经授权的仓库。</p></details>
      <section class="split-settings"><div><h2>导入代码版本</h2><p>分析固定提交的代码与可达历史。依赖比较需要导入变更前、变更后两个版本。</p><details class="disclosure"><summary>从哪里获取 SHA？</summary><p>在 GitHub 仓库的提交记录中打开目标提交，复制完整 SHA。</p><a v-if="repositories.find(r => r.id === selectedRepo)" class="text-link" :href="`https://github.com/${repositories.find(r => r.id === selectedRepo)?.owner}/${repositories.find(r => r.id === selectedRepo)?.name}/commits`" target="_blank" rel="noopener noreferrer">打开 GitHub 提交历史<AppIcon name="external" :size="14" /></a></details></div><div><form class="setting-fields" @submit.prevent="synchronize"><label class="field"><span>完整提交 SHA</span><input v-model="commitSha" required pattern="[a-fA-F0-9]{40}" maxlength="40" aria-describedby="sha-help" placeholder="粘贴 40 位提交 SHA" /><small id="sha-help">例如 {{ EXAMPLES.INCIDENT_FIX.head.slice(0, 12) }}…；完整 SHA 为 40 位。</small></label><div><button class="button primary" :disabled="busy || !selectedRepo || (!!job && !['READY','FAILED'].includes(job.status))"><AppIcon name="plus" :size="15" />导入版本</button></div></form><p v-if="job" role="status" class="inline-success"><AppIcon :name="job.status === 'READY' ? 'check' : 'activity'" :size="16" />{{ ({ QUEUED: '等待同步', FETCHING: '正在获取提交', INDEXING: '正在建立代码索引', READY: '版本已就绪，可以开始分析', FAILED: '导入失败，请重试' } as Record<string,string>)[job.status] ?? job.status }} · {{ shortSha(job.targetSha) }}</p></div></section>
      <details v-if="selectedDemoRepo" class="disclosure"><summary>导入真实仓库示例所需版本</summary><p class="field-help">示例使用 Spring PetClinic 仓库。点击填入 SHA，再使用上面的“导入版本”。已有版本会直接复用。</p><div v-for="example in [{ title: 'Admin 升级前', sha: EXAMPLES.DEPENDENCY_IMPACT.base }, { title: 'Admin 升级后', sha: EXAMPLES.DEPENDENCY_IMPACT.head }, { title: 'Zipkin 历史修复知识版本', sha: EXAMPLES.INCIDENT_FIX.head }]" :key="example.sha" class="version-row"><div><strong>{{ example.title }}</strong><code>{{ shortSha(example.sha) }}</code></div><button class="button secondary" @click="commitSha = example.sha">填入 SHA</button></div></details>
      <section class="section-block" style="margin-top: 32px"><div class="section-header"><h2>可分析的代码版本</h2><span class="muted">{{ snapshots.length }} 个版本</span></div><p v-if="!snapshots.length">还没有就绪版本。先导入一个提交，再返回分析工作台。</p><div class="version-list"><article v-for="snapshot in snapshots" :key="snapshot.id" class="version-row"><AppIcon name="branch" /><div><strong>{{ labels[snapshot.id]?.subject || '代码版本' }}</strong><p><code>{{ shortSha(snapshot.commitSha) }}</code> · {{ snapshot.stats.fileCount ?? 0 }} 个文件 · {{ snapshot.stats.commitCount ?? 0 }} 个历史提交</p></div><RouterLink class="button secondary" :to="{ path: '/', query: { repo: selectedRepo, snapshot: snapshot.id } }">使用此版本<AppIcon name="arrow" :size="14" /></RouterLink></article></div></section>
    </template>
      <details v-if="mayRevoke" class="disclosure"><summary>管理当前仓库的访问授权</summary><p class="field-help">撤销后 Agent 将无法读取这个仓库；本机代码镜像与索引会保留。需要时可重新接入授权。</p><button class="text-button danger-button" :disabled="busy" @click="revoke">撤销 {{ repositories.find(r => r.id === selectedRepo)?.owner }}/{{ repositories.find(r => r.id === selectedRepo)?.name }} 的读取授权</button></details>
  </template>
  <section v-else-if="tab === 'search'" class="section-block"><h2>检索代码与历史记录</h2><p>用于手动核对证据。想直接获得分析结论，请返回分析工作台。</p><label class="field"><span>查阅的代码版本</span><select v-model="snapshotId" @change="page = null; evidence = null"><option v-for="s in snapshots" :key="s.id" :value="s.id">{{ shortSha(s.commitSha) }} · {{ labels[s.id]?.subject || '代码版本' }}</option></select></label><form class="inline-form" @submit.prevent="search()"><label class="field"><span>异常、符号、路径或提交 SHA</span><input v-model="query" required maxlength="200" /></label><label class="field"><span>证据类型</span><select v-model="sourceType"><option value="">全部证据</option><option value="FILE">当前代码</option><option value="COMMIT">提交记录</option><option value="DIFF">历史修改</option></select></label><button class="button primary" :disabled="busy || !snapshotId"><AppIcon name="search" :size="15" />检索</button></form><p v-if="page" class="field-help">本页 {{ page.items.length }} 条{{ !page.items.length ? '，当前版本内未找到匹配内容，可尝试更具体的异常或配置键。' : '' }}</p><article v-for="item in page?.items" :key="item.id" class="evidence-search-row"><header><strong>{{ item.path || shortSha(item.sourceSha) }}</strong><a class="text-link" :href="item.sourceUrl" target="_blank" rel="noopener noreferrer">GitHub 原文<AppIcon name="external" :size="14" /></a></header><p class="muted">{{ item.symbol }} · {{ shortSha(item.sourceSha) }} · 第 {{ item.startLine }}–{{ item.endLine }} 行{{ item.truncated ? ' · 片段已截断' : '' }}</p><pre class="source-code">{{ item.excerpt }}</pre><details v-if="item.type === 'DIFF'" class="technical-warnings"><summary>新旧两侧定位</summary><pre>{{ JSON.stringify(item.locator, null, 2) }}</pre></details></article><button v-if="page?.nextCursor" class="button secondary" :disabled="busy" @click="search(page.nextCursor)">下一页</button></section>
  <section v-else class="section-block"><h2>手动比较声明依赖</h2><p>不经过模型，直接核对两个版本中的 Maven 依赖和服务使用路径。</p><form class="setting-fields" @submit.prevent="compare"><div class="field-grid"><label class="field"><span>变更前版本</span><select v-model="baseId"><option v-for="s in snapshots" :key="s.id" :value="s.id">{{ shortSha(s.commitSha) }} · {{ labels[s.id]?.subject }}</option></select></label><label class="field"><span>变更后版本</span><select v-model="headId"><option v-for="s in snapshots" :key="s.id" :value="s.id">{{ shortSha(s.commitSha) }} · {{ labels[s.id]?.subject }}</option></select></label></div><label class="field"><span>模块路径 <span class="optional">可选</span></span><input v-model="modulePath" placeholder="留空比较整个仓库" /></label><div><button class="button primary" :disabled="busy || !baseId || !headId">比较依赖<AppIcon name="arrow" :size="15" /></button></div></form><template v-if="result"><h3 class="answer-title">{{ result.affectedServices.length }} 个服务存在声明依赖影响</h3><p>{{ result.affectedServices.join('、') || '未识别到明确影响，不代表没有其他影响。' }}</p><div v-for="change in result.changes" :key="change.id" class="version-row"><div><strong>{{ change.coordinate }}</strong><p>{{ change.module }}</p></div><code>{{ change.before?.version ?? '未声明' }} → {{ change.after?.version ?? '已移除' }}</code></div><details class="disclosure"><summary>传播路径与依据</summary><article v-for="(impact, i) in result.impacts" :key="i" class="path-row"><p>{{ impact.path.join(' → ') }}</p><button v-for="(id, n) in impact.evidenceIds" :key="id" class="text-button" :disabled="busy" @click="showEvidence(id, impact.view)">核对依据 {{ n + 1 }}</button></article></details><details class="disclosure"><summary>范围限制与未核验项（{{ result.warnings.length }}）</summary><ul class="plain-list"><li v-for="warning in result.warnings" :key="warning"><code>{{ warning }}</code></li></ul></details></template><article v-if="evidence" class="evidence-search-row"><h3>已核验证据</h3><a class="text-link" :href="evidence.sourceUrl" target="_blank" rel="noopener noreferrer">{{ evidence.path }} · {{ shortSha(evidence.sourceSha) }}<AppIcon name="external" :size="14" /></a><pre class="source-code">{{ evidence.excerpt }}</pre></article></section>
</template>
