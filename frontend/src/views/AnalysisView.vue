<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { request, type Repository, type Snapshot } from '../api/repository'
import { cancelAnalysis, getAnalysis, submitAnalysis, type AnalysisResult, type Task } from '../api/analysis'

const route = useRoute(), router = useRouter()
const repositories = ref<Repository[]>([]), snapshots = ref<Snapshot[]>([]), modules = ref<{ path: string; service: boolean }[]>([])
const repoId = ref<number>(), snapshotId = ref<number>(), baseId = ref<number>(), task = ref<Task>('DEPENDENCY_IMPACT')
const modulePath = ref(''), deployedSha = ref(''), question = ref(''), error = ref(''), busy = ref(false), loading = ref(false)
const result = ref<AnalysisResult | null>(null), selectedEvidence = ref<string | null>(null)
const controller = new AbortController()
let poll: ReturnType<typeof setTimeout> | undefined
let snapshotGeneration = 0, graphGeneration = 0
const activeCitation = computed(() => result.value?.citations.find(c => c.id === selectedEvidence.value))
const statusNames: Record<string, string> = { RUNNING: '正在分析', ANSWERED: '分析完成', PARTIAL: '有证据的局部结论', REFUSED: '明确拒答', NEEDS_INPUT: '需要补充信息', UNAVAILABLE: '暂不可用', CANCELLED: '已取消' }
const toolNames: Record<string, string> = { compareDependencyChanges: '比较依赖与影响路径', searchFixCandidates: '检索历史修复并核验版本', searchRepository: '检索仓库证据', readEvidence: '读取原文证据' }
const applicability: Record<string, string> = { DEPLOYED_VERSION_UNKNOWN: '缺少部署版本，适用性未知', COMMIT_PRESENT_IN_DEPLOYED_ANCESTRY: '部署版本已包含此提交，需排查其他原因', COMMIT_NOT_IN_DEPLOYED_ANCESTRY: '部署祖先未包含此 SHA；等价补丁与回滚尚未核验' }
function message(e: unknown) {
  const code = e instanceof Error ? e.message : String(e)
  const known: Record<string, string> = { REPOSITORY_NOT_ALLOWED: '当前仓库不在服务端允许范围，拒绝访问。', SNAPSHOT_NOT_READY: '该版本尚未就绪或不属于当前仓库。', UNKNOWN_SERVICE_MODULE: '模块路径不属于所选版本。', ANALYSIS_QUEUE_FULL: '分析任务已满，请稍后重试。' }
  return known[code] ?? code
}
async function loadSnapshots() {
  const generation = ++snapshotGeneration
  ++graphGeneration
  snapshots.value = []; modules.value = []; snapshotId.value = undefined; baseId.value = undefined; modulePath.value = ''; error.value = ''
  if (!repoId.value) return
  loading.value = true
  try {
    const list = await request<Snapshot[]>(`/repositories/${repoId.value}/snapshots`, { signal: controller.signal })
    if (generation !== snapshotGeneration) return
    snapshots.value = list; snapshotId.value = list[0]?.id
    baseId.value = list.find(s => s.commitSha !== list[0]?.commitSha)?.id
  } catch (e) { if (!controller.signal.aborted && generation === snapshotGeneration) error.value = message(e) }
  finally { if (generation === snapshotGeneration) loading.value = false }
}
async function loadModules() {
  const generation = ++graphGeneration
  modules.value = []; modulePath.value = ''
  if (!repoId.value || !snapshotId.value) return
  try {
    const graph = await request<{ modules: { path: string; service: boolean }[] }>(`/repositories/${repoId.value}/snapshots/${snapshotId.value}/dependency-graph`, { signal: controller.signal })
    if (generation === graphGeneration) modules.value = graph.modules
  } catch (e) { if (!controller.signal.aborted && generation === graphGeneration) error.value = message(e) }
}
watch(repoId, () => { void loadSnapshots() })
watch(snapshotId, () => { void loadModules() })
function example() {
  question.value = task.value === 'DEPENDENCY_IMPACT' ? '所选模块的依赖变更会影响哪些服务？请给出依赖路径和证据。' : '出现 zipkin tracing 告警，检查 spring-boot-starter-zipkin 相关的历史修复 commit。无法确认关联时请明确说明。'
}
async function refreshRun(id: string) {
  try {
    result.value = await getAnalysis(id, controller.signal)
    busy.value = result.value.status === 'RUNNING'
    if (busy.value) poll = setTimeout(() => { void refreshRun(id) }, 1000)
  } catch (e) {
    if (!controller.signal.aborted) { error.value = `读取分析任务失败：${message(e)}。可以刷新页面恢复。`; busy.value = false }
  }
}
async function submit() {
  if (!repoId.value || !snapshotId.value || !question.value.trim() || (task.value === 'DEPENDENCY_IMPACT' && !baseId.value)) return
  clearTimeout(poll); busy.value = true; error.value = ''; result.value = null; selectedEvidence.value = null
  try {
    result.value = await submitAnalysis({ repoId: repoId.value, snapshotId: snapshotId.value, task: task.value, question: question.value,
      ...(task.value === 'DEPENDENCY_IMPACT' ? { baseSnapshotId: baseId.value } : {}),
      ...(modulePath.value ? { module: modulePath.value } : {}),
      ...(task.value === 'INCIDENT_FIX' && deployedSha.value.trim() ? { deployedSha: deployedSha.value.trim() } : {}) }, controller.signal)
    await router.replace({ path: '/', query: { run: result.value.id } })
    if (result.value.status === 'RUNNING') void refreshRun(result.value.id)
    else busy.value = false
  } catch (e) { if (!controller.signal.aborted) error.value = message(e); busy.value = false }
}
function safeLink(url: string) { return url.startsWith(`https://github.com/${result.value?.repository}/`) ? url : undefined }
async function cancel() {
  if (!result.value || !busy.value) return
  clearTimeout(poll)
  try { result.value = await cancelAnalysis(result.value.id, controller.signal); busy.value = false }
  catch (e) { if (!controller.signal.aborted) { error.value = message(e); void refreshRun(result.value.id) } }
}
onMounted(async () => {
  try {
    repositories.value = await request<Repository[]>('/repositories', { signal: controller.signal })
    repoId.value = repositories.value[0]?.id
    const saved = route.query.run
    if (typeof saved === 'string' && /^[a-f0-9-]{36}$/.test(saved)) { busy.value = true; void refreshRun(saved) }
  } catch (e) { if (!controller.signal.aborted) error.value = message(e) }
})
onUnmounted(() => { clearTimeout(poll); controller.abort() })
</script>

<template>
  <header class="page-header"><span class="eyebrow">REPOPILOT / AGENT</span><RouterLink class="text-link" to="/repositories">管理仓库与版本 ↗</RouterLink></header>
  <section class="analysis-heading"><p class="eyebrow">仓库限定 · 只读工具 · 证据核验</p><h1>从一个问题，<br />追到代码证据。</h1><p class="hero-description">分析依赖变更的服务影响，或从告警定位历史修复线索。超出范围、权限不足或没有证据时，明确说明。</p></section>
  <form class="repo-panel analysis-form" @submit.prevent="submit">
    <fieldset :disabled="busy"><legend>发起分析</legend>
      <div class="task-options">
        <label :class="{ chosen: task === 'DEPENDENCY_IMPACT' }"><input v-model="task" type="radio" value="DEPENDENCY_IMPACT" />依赖变更影响</label>
        <label :class="{ chosen: task === 'INCIDENT_FIX' }"><input v-model="task" type="radio" value="INCIDENT_FIX" />告警历史修复</label>
      </div>
      <div class="repo-form">
        <label>GitHub 仓库<select v-model="repoId" aria-label="GitHub 仓库" required><option v-for="repo in repositories" :key="repo.id" :value="repo.id">{{ repo.owner }}/{{ repo.name }}</option></select></label>
        <label>{{ task === 'DEPENDENCY_IMPACT' ? '变更后版本 / HEAD' : '可查历史的知识版本' }}<select v-model="snapshotId" :aria-label="task === 'DEPENDENCY_IMPACT' ? '变更后版本 / HEAD' : '可查历史的知识版本'" required :disabled="loading"><option v-for="snapshot in snapshots" :key="snapshot.id" :value="snapshot.id">{{ snapshot.commitSha.slice(0, 12) }} · 快照 {{ snapshot.id }}</option></select></label>
      </div>
      <div class="repo-form">
        <label v-if="task === 'DEPENDENCY_IMPACT'">变更前版本 / BASE<select v-model="baseId" aria-label="变更前版本 / BASE" required><option :value="undefined" disabled>选择比较基准</option><option v-for="snapshot in snapshots" :key="snapshot.id" :value="snapshot.id">{{ snapshot.commitSha.slice(0, 12) }} · 快照 {{ snapshot.id }}</option></select></label>
        <label v-else>部署版本 SHA（可选）<input v-model="deployedSha" placeholder="40 位完整 SHA；不填则标注部署适用性未知" pattern="[a-fA-F0-9]{40}" /></label>
        <label>{{ task === 'DEPENDENCY_IMPACT' ? '模块范围' : '告警归属模块' }}<select v-model="modulePath" :aria-label="task === 'DEPENDENCY_IMPACT' ? '模块范围' : '告警归属模块'"><option value="">整个仓库</option><option v-for="module in modules" :key="module.path" :value="module.path">{{ module.path }}{{ module.service ? ' · 服务' : '' }}</option></select></label>
      </div>
      <label class="question-label" for="analysis-question">{{ task === 'DEPENDENCY_IMPACT' ? '想了解什么影响？' : '告警内容、异常或堆栈关键字' }}</label>
      <textarea id="analysis-question" v-model="question" required maxlength="8000" rows="4" placeholder="输入与当前仓库有关的问题；日志建议保留异常类型、配置键和类名。" />
      <div class="analysis-actions"><button type="button" @click="example">填入示例问题</button><button class="primary-button" type="submit" :disabled="!snapshotId || !question.trim() || loading || (task === 'DEPENDENCY_IMPACT' && !baseId)">开始分析 ↗</button></div>
    </fieldset>
    <p v-if="!loading && !snapshots.length" class="repo-note">还没有可用版本，请先在<RouterLink class="text-link" to="/repositories">仓库管理页</RouterLink>同步完整 commit SHA。</p>
  </form>
  <p v-if="error" class="notice error" role="alert">{{ error }}</p>
  <section v-if="result" class="repo-panel analysis-result" aria-live="polite" :aria-busy="busy">
    <div class="section-title"><h2>{{ statusNames[result.status] ?? result.status }}</h2><span class="phase-tag">{{ result.reasonCode }}</span></div>
    <p v-if="busy" role="status">Agent 正在选择只读工具、收集并核验证据… <button type="button" @click="cancel">取消分析</button></p><p v-else class="result-summary">{{ result.summary }}</p>
    <p class="repo-note">{{ result.repository }} · 知识版本 {{ result.knowledgeSha }}<br v-if="result.baseSha" /><template v-if="result.baseSha">基准版本 {{ result.baseSha }}</template></p>
    <p v-if="result.status === 'UNAVAILABLE'" class="repo-note">本机配置模型后，执行 <code>python3 scripts/dev.py agent</code> 启动支持模型的后端。</p>
    <div v-if="result.events.length" class="execution-trace"><h3>工具执行记录</h3><ol><li v-for="event in result.events" :key="event.sequence"><strong>{{ toolNames[event.tool] ?? event.tool }}</strong><span>{{ event.status }} · {{ event.cached ? '复用缓存' : `${event.durationMs} ms` }} · {{ event.code }}</span></li></ol></div>
    <template v-if="result.dependency"><h3>声明依赖影响</h3><p>{{ result.dependency.affectedServices.join('、') || '本次范围内未识别到影响服务；不代表没有其他影响。' }}</p><div class="table-scroll"><table><thead><tr><th>模块</th><th>依赖</th><th>版本变化</th></tr></thead><tbody><tr v-for="change in result.dependency.changes" :key="change.id"><td>{{ change.module }}</td><td>{{ change.coordinate }}</td><td>{{ change.before?.version ?? '未声明' }} → {{ change.after?.version ?? '已移除' }}</td></tr></tbody></table></div>
      <details v-if="result.dependency.impacts.length"><summary>查看依赖路径与证据</summary><div v-for="(impact, i) in result.dependency.impacts" :key="i" class="evidence-card"><strong>{{ impact.service }}</strong> · {{ impact.view }} · {{ impact.scope }}<p>{{ impact.path.join(' → ') }}</p><button v-for="eid in impact.evidenceIds" :key="eid" type="button" @click="selectedEvidence = eid">证据 {{ eid.slice(0, 8) }}</button></div></details>
    </template>
    <template v-if="result.incident"><h3>相似历史修复候选</h3><article v-for="candidate in result.incident.candidates" :key="candidate.commitSha" class="evidence-card"><strong>{{ candidate.subject }}</strong><p><a :href="`https://github.com/${result.repository}/commit/${candidate.commitSha}`" target="_blank" rel="noopener noreferrer">{{ candidate.commitSha }} ↗</a></p><p>{{ applicability[candidate.applicability] ?? candidate.applicability }}</p><p class="repo-note">匹配词：{{ candidate.matchedTerms.join('、') }} · 相似候选，尚未确认事故关联</p><button v-for="evidence in candidate.evidence" :key="evidence.id" type="button" @click="selectedEvidence = evidence.id">查看 {{ evidence.id.slice(0, 8) }}</button></article></template>
    <div v-if="result.citations.length" class="evidence-browser"><h3>原文证据 · {{ result.citations.length }} 项</h3><div class="evidence-tabs"><button v-for="citation in result.citations" :key="citation.id" type="button" :aria-pressed="selectedEvidence === citation.id" @click="selectedEvidence = citation.id">{{ citation.type }} · {{ citation.path || citation.sourceSha.slice(0, 8) }}</button></div><article v-if="activeCitation" class="evidence-card"><p>{{ activeCitation.path || '提交说明' }} · {{ activeCitation.startLine }}–{{ activeCitation.endLine }} · 快照 {{ activeCitation.snapshotId }}</p><pre>{{ activeCitation.excerpt }}</pre><a class="text-link" :href="safeLink(activeCitation.sourceUrl)" target="_blank" rel="noopener noreferrer">到 GitHub 核对完整原文 ↗</a></article></div>
    <details v-if="result.warnings.length"><summary>覆盖范围与未核验项（{{ result.warnings.length }}）</summary><ul><li v-for="warning in result.warnings" :key="warning">{{ warning }}</li></ul></details>
    <footer class="run-usage"><span>模型 {{ result.usage.modelCalls }} 次</span><span>工具实际执行 {{ result.usage.executedToolCalls }} 次</span><span>执行失败 {{ result.usage.failedToolCalls }} 次</span><span>参数或权限拦截 {{ result.usage.rejectedToolCalls }} 次</span><span>缓存 {{ result.usage.cachedToolCalls }} 次</span><span>输入/输出 token {{ result.usage.inputTokens }}/{{ result.usage.outputTokens }}{{ result.usage.estimatedTokens ? '（含保守估算）' : '' }}</span><span>{{ (result.usage.durationMs / 1000).toFixed(1) }} 秒</span></footer>
  </section>
</template>
