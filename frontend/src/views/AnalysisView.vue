<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { request, type Repository, type Snapshot } from '../api/repository'
import { cancelAnalysis, getAnalysis, submitAnalysis, type AnalysisResult, type Task } from '../api/analysis'
import AppIcon from '../components/AppIcon.vue'
import AnalysisResultPanel from '../components/AnalysisResultPanel.vue'
import { EXAMPLES, friendlyError, loadCommitLabels, shortModule, shortSha, type CommitLabel } from '../lib/presentation'
import { useHistoryStore } from '../stores/history'
const route = useRoute(), router = useRouter(), history = useHistoryStore()
const repositories = ref<Repository[]>([]), snapshots = ref<Snapshot[]>([]), modules = ref<{ path: string; service: boolean }[]>([]), labels = ref<Record<number, CommitLabel>>({})
const repoId = ref<number>(), snapshotId = ref<number>(), baseId = ref<number>(), task = ref<Task>('DEPENDENCY_IMPACT')
const modulePath = ref(''), deployedSha = ref(''), question = ref(''), error = ref(''), notice = ref('')
const loading = ref(true), modulesLoading = ref(false), submitting = ref(false), cancelling = ref(false), rangeOpen = ref(false)
const result = ref<AnalysisResult | null>(null), questionInput = ref<HTMLTextAreaElement>(), resultElement = ref<HTMLElement>()
const controller = new AbortController()
let poll: ReturnType<typeof setTimeout> | undefined, scopeGeneration = 0, moduleGeneration = 0, runGeneration = 0
const rangeTouched = ref(false)
const busy = computed(() => submitting.value || result.value?.status === 'RUNNING')
const selectedVersion = computed(() => snapshots.value.find(s => s.id === snapshotId.value))
const selectedBase = computed(() => snapshots.value.find(s => s.id === baseId.value))
const preset = computed(() => EXAMPLES[task.value])
const canSubmit = computed(() => !busy.value && !loading.value && !modulesLoading.value && !!repoId.value && !!snapshotId.value && !!question.value.trim() && (task.value !== 'DEPENDENCY_IMPACT' || (!!baseId.value && selectedBase.value?.commitSha !== selectedVersion.value?.commitSha)))
const readiness = computed(() => loading.value ? '正在读取仓库版本…' : !repoId.value ? '先接入一个 GitHub 仓库' : !snapshotId.value ? '先导入一个代码版本' : task.value === 'DEPENDENCY_IMPACT' && (!baseId.value || selectedBase.value?.commitSha === selectedVersion.value?.commitSha) ? '请选择两个不同的代码版本进行比较' : !question.value.trim() ? '输入问题，或填入真实仓库示例' : modulesLoading.value ? '正在读取模块…' : '分析只读代码，不会修改仓库')
function versionText(snapshot: Snapshot) { return `${shortSha(snapshot.commitSha)}${labels.value[snapshot.id]?.subject ? ` · ${labels.value[snapshot.id]!.subject}` : ''}` }
async function loadModules() {
  const generation = ++moduleGeneration, repository = repoId.value, snapshot = snapshotId.value
  modules.value = []; modulesLoading.value = true
  try {
    if (!repository || !snapshot) return
    const graph = await request<{ modules: { path: string; service: boolean }[] }>(`/repositories/${repository}/snapshots/${snapshot}/dependency-graph`, { signal: controller.signal })
    if (generation !== moduleGeneration) return
    modules.value = graph.modules
    if (modulePath.value && !graph.modules.some(m => m.path === modulePath.value)) modulePath.value = ''
  } catch (cause) { if (!controller.signal.aborted && generation === moduleGeneration) error.value = friendlyError(cause) }
  finally { if (generation === moduleGeneration) modulesLoading.value = false }
}
async function loadScope() {
  const generation = ++scopeGeneration, repository = repoId.value
  ++moduleGeneration; loading.value = true; error.value = ''; notice.value = ''; snapshots.value = []; labels.value = {}; modules.value = []; modulePath.value = ''; snapshotId.value = undefined; baseId.value = undefined; rangeTouched.value = false
  try {
    if (!repository) return
    const list = await request<Snapshot[]>(`/repositories/${repository}/snapshots`, { signal: controller.signal })
    if (generation !== scopeGeneration) return
    snapshots.value = list; snapshotId.value = list[0]?.id; baseId.value = list.find(s => s.commitSha !== list[0]?.commitSha)?.id
    await loadModules()
    void loadCommitLabels(repository, list).then(metadata => {
      if (generation !== scopeGeneration) return
      labels.value = metadata
      const parent = list.find(s => metadata[list[0]?.id ?? 0]?.parents.includes(s.commitSha))
      if (parent && !rangeTouched.value && snapshotId.value === list[0]?.id) baseId.value = parent.id
    })
  } catch (cause) { if (!controller.signal.aborted && generation === scopeGeneration) error.value = friendlyError(cause) }
  finally { if (generation === scopeGeneration) loading.value = false }
}
function switchTask(value: Task) {
  if (busy.value) return
  if (question.value === EXAMPLES[task.value].question) question.value = ''
  task.value = value; notice.value = ''; error.value = ''
  if (value === 'DEPENDENCY_IMPACT' && !baseId.value) baseId.value = snapshots.value.find(s => s.id !== snapshotId.value)?.id
}
async function useExample() {
  if (busy.value) return
  const repo = repositories.value.find(r => `${r.owner}/${r.name}` === 'spring-petclinic/spring-petclinic-microservices')
  if (!repo) { error.value = '真实示例需要先接入 Spring PetClinic 仓库。请前往仓库管理。'; return }
  if (repoId.value !== repo.id) { repoId.value = repo.id; await loadScope() }
  const example = EXAMPLES[task.value], head = snapshots.value.find(s => s.commitSha === example.head), base = snapshots.value.find(s => s.commitSha === example.base)
  if (!head || (task.value === 'DEPENDENCY_IMPACT' && !base)) { error.value = '示例使用的代码版本尚未导入。请在仓库管理中导入示例版本后重试。'; return }
  rangeTouched.value = true; snapshotId.value = head.id; baseId.value = base?.id; modulePath.value = example.module; deployedSha.value = ''; question.value = example.question
  await loadModules()
  notice.value = '已填入真实仓库示例，点击“开始分析”即可查看结果。'
  await nextTick(); questionInput.value?.focus()
}
async function refreshRun(id: string, generation: number) {
  try {
    const current = await getAnalysis(id, controller.signal)
    if (generation !== runGeneration) return
    result.value = current
    if (current.status === 'RUNNING') poll = setTimeout(() => { void refreshRun(id, generation) }, 1000)
  } catch (cause) { if (!controller.signal.aborted && generation === runGeneration) error.value = `结果读取失败。${friendlyError(cause)}` }
}
async function restoreRun(value: unknown) {
  if (typeof value !== 'string' || !/^[a-f0-9-]{36}$/.test(value)) { clearTimeout(poll); ++runGeneration; result.value = null; return }
  if (result.value?.id === value) return
  clearTimeout(poll); const generation = ++runGeneration; error.value = ''; result.value = null
  await refreshRun(value, generation)
  const restored = result.value as AnalysisResult | null
  if (restored) task.value = restored.incident ? 'INCIDENT_FIX' : history.entries.find(e => e.id === value)?.task ?? 'DEPENDENCY_IMPACT'
}
watch(() => route.query.run, value => { void restoreRun(value) })
async function submit() {
  if (!canSubmit.value || !repoId.value || !snapshotId.value) return
  clearTimeout(poll); const generation = ++runGeneration; submitting.value = true; error.value = ''; notice.value = ''; result.value = null
  try {
    const created = await submitAnalysis({ repoId: repoId.value, snapshotId: snapshotId.value, task: task.value, question: question.value,
      ...(task.value === 'DEPENDENCY_IMPACT' ? { baseSnapshotId: baseId.value } : {}), ...(modulePath.value ? { module: modulePath.value } : {}),
      ...(task.value === 'INCIDENT_FIX' && deployedSha.value.trim() ? { deployedSha: deployedSha.value.trim() } : {}) }, controller.signal)
    if (generation !== runGeneration) return
    result.value = created; history.add(created.id, task.value)
    await router.replace({ path: '/', query: { run: created.id } })
    await nextTick(); resultElement.value?.scrollIntoView({ behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth', block: 'start' })
    if (created.status === 'RUNNING') void refreshRun(created.id, generation)
  } catch (cause) { if (!controller.signal.aborted && generation === runGeneration) error.value = friendlyError(cause) }
  finally { submitting.value = false }
}
async function cancel() {
  const current = result.value; if (!current || !busy.value) return
  cancelling.value = true
  try { const cancelled = await cancelAnalysis(current.id, controller.signal); if (result.value?.id === current.id) { clearTimeout(poll); ++runGeneration; result.value = cancelled } }
  catch (cause) { if (!controller.signal.aborted) error.value = friendlyError(cause) }
  finally { cancelling.value = false }
}
async function reset() { clearTimeout(poll); ++runGeneration; result.value = null; question.value = ''; error.value = ''; notice.value = ''; await router.replace('/'); questionInput.value?.focus() }
async function initialize() {
  loading.value = true; error.value = ''
  try {
    repositories.value = await request<Repository[]>('/repositories', { signal: controller.signal })
    repoId.value = repositories.value.find(r => r.id === Number(route.query.repo))?.id ?? repositories.value[0]?.id
    await loadScope()
    const requested = snapshots.value.find(s => s.id === Number(route.query.snapshot))
    if (requested) { snapshotId.value = requested.id; rangeTouched.value = true; await loadModules() }
    if (route.query.run) await restoreRun(route.query.run)
    else if (route.query.example === 'impact' || route.query.example === 'fix') { task.value = route.query.example === 'fix' ? 'INCIDENT_FIX' : 'DEPENDENCY_IMPACT'; await useExample() }
  } catch (cause) { if (!controller.signal.aborted) error.value = friendlyError(cause); loading.value = false }
}
onMounted(() => { void initialize() })
onUnmounted(() => { clearTimeout(poll); ++runGeneration; controller.abort() })
</script>
<template>
  <header class="page-heading"><div><h1>分析工作台</h1><p>查清依赖变更影响，或从告警找到历史修复线索。</p></div><button v-if="result" class="button secondary" @click="reset"><AppIcon name="plus" :size="16" />新建分析</button></header>
  <div v-if="error" class="inline-error" role="alert"><AppIcon name="warning" /><div>{{ error }} <RouterLink v-if="error.includes('导入') || error.includes('接入')" to="/repositories">前往仓库管理</RouterLink><button v-else-if="result" class="text-button" @click="refreshRun(result.id, runGeneration)">重新读取结果</button><button v-else class="text-button" @click="repoId ? loadScope() : initialize()">重新连接</button></div></div>
  <div v-if="!loading && !repositories.length && !error" class="empty-state"><AppIcon name="folder" :size="28" /><h2>先接入一个 GitHub 仓库</h2><p>导入仓库代码和提交历史后，就可以分析依赖影响和告警修复。</p><RouterLink class="button primary" to="/repositories">接入仓库<AppIcon name="arrow" :size="16" /></RouterLink></div>
  <template v-else-if="repositories.length || loading">
    <div class="analysis-layout">
      <form class="composer" @submit.prevent="submit">
        <fieldset :disabled="busy || loading">
          <div class="task-tabs" aria-label="选择分析任务"><label :class="{ selected: task === 'DEPENDENCY_IMPACT' }"><input type="radio" name="analysis-task" :checked="task === 'DEPENDENCY_IMPACT'" aria-label="分析依赖变化" @change="switchTask('DEPENDENCY_IMPACT')" /><AppIcon name="branch" />分析依赖变化</label><label :class="{ selected: task === 'INCIDENT_FIX' }"><input type="radio" name="analysis-task" :checked="task === 'INCIDENT_FIX'" aria-label="查找历史修复" @change="switchTask('INCIDENT_FIX')" /><AppIcon name="search" />查找历史修复</label></div>
          <label class="field repository-context"><AppIcon name="folder" :size="16" /><span class="sr-only">GitHub 仓库</span><select v-model="repoId" aria-label="GitHub 仓库" @change="loadScope"><option v-for="repo in repositories" :key="repo.id" :value="repo.id">{{ repo.owner }}/{{ repo.name }}</option></select><span class="context-count">{{ snapshots.length }} 个可用版本</span></label>
          <button type="button" class="text-button mobile-example-button" :disabled="loading || busy" @click="useExample"><AppIcon name="plus" :size="15" />填入真实仓库示例</button>
          <label class="question-label" for="analysis-question">{{ task === 'DEPENDENCY_IMPACT' ? '你想了解哪项变更？' : '发生了什么告警？' }}</label>
          <textarea id="analysis-question" ref="questionInput" v-model="question" required maxlength="8000" rows="4" :placeholder="task === 'DEPENDENCY_IMPACT' ? '例如：这个模块的依赖升级，会影响哪些服务？' : '粘贴异常、关键日志或堆栈，例如 zipkin tracing 相关报错…'" @keydown.ctrl.enter.prevent="submit" @keydown.meta.enter.prevent="submit" />
          <div class="scope-summary"><div><span>分析范围</span><strong>{{ modulePath ? shortModule(modulePath) : '整个仓库' }}</strong><code v-if="selectedVersion">{{ task === 'DEPENDENCY_IMPACT' && selectedBase ? `${shortSha(selectedBase.commitSha)} → ` : '' }}{{ shortSha(selectedVersion.commitSha) }}</code></div><button type="button" class="text-button" :aria-expanded="rangeOpen" aria-controls="analysis-scope" @click="rangeOpen = !rangeOpen">{{ rangeOpen ? '收起范围' : '调整范围' }}<AppIcon :name="rangeOpen ? 'down' : 'settings'" :size="15" /></button></div>
          <div v-if="rangeOpen" id="analysis-scope" class="scope-fields">
            <div class="field-grid"><label v-if="task === 'DEPENDENCY_IMPACT'" class="field"><span>变更前版本</span><select v-model="baseId" aria-label="变更前版本" @change="rangeTouched = true"><option :value="undefined" disabled>选择基准版本</option><option v-for="snapshot in snapshots" :key="snapshot.id" :value="snapshot.id">{{ versionText(snapshot) }}</option></select></label><label class="field"><span>{{ task === 'DEPENDENCY_IMPACT' ? '变更后版本' : '查阅的代码版本' }}</span><select v-model="snapshotId" :aria-label="task === 'DEPENDENCY_IMPACT' ? '变更后版本' : '查阅的代码版本'" @change="rangeTouched = true; loadModules()"><option v-for="snapshot in snapshots" :key="snapshot.id" :value="snapshot.id">{{ versionText(snapshot) }}</option></select></label></div>
            <label class="field"><span>{{ task === 'DEPENDENCY_IMPACT' ? '分析模块' : '告警所属模块' }}</span><select v-model="modulePath" :aria-label="task === 'DEPENDENCY_IMPACT' ? '分析模块' : '告警所属模块'" :disabled="modulesLoading"><option value="">整个仓库</option><option v-for="module in modules" :key="module.path" :value="module.path">{{ shortModule(module.path) }}{{ module.service ? '（服务）' : '' }}</option></select></label>
            <label v-if="task === 'INCIDENT_FIX'" class="field"><span>部署版本 SHA <span class="optional">可选</span></span><input v-model="deployedSha" aria-label="部署版本 SHA" pattern="[a-fA-F0-9]{40}" placeholder="粘贴完整的 40 位提交 SHA" aria-describedby="deployed-help" /><small id="deployed-help">用于判断修复提交是否已包含在部署历史中。不填时，适用性会标注为未知。</small></label>
          </div>
          <div class="composer-actions"><span class="field-help">{{ readiness }}</span><button type="submit" class="button primary" :disabled="!canSubmit">{{ busy ? '正在分析' : '开始分析' }}<AppIcon name="arrow" :size="16" /></button></div>
        </fieldset>
        <p v-if="notice" class="inline-success" role="status"><AppIcon name="check" :size="16" />{{ notice }}</p>
      </form>
      <aside class="analysis-guide"><h2>第一次使用？从示例开始</h2><p>用已导入的真实仓库，走完一次完整分析。</p><button class="example-button" :disabled="loading || busy" @click="useExample"><span>{{ preset.title }}</span><p>{{ preset.description }}</p><span class="example-action">填入真实仓库示例<AppIcon name="arrow" :size="15" /></span></button><div class="expected-output"><h3>分析后，你会得到</h3><ul><li><AppIcon name="check" :size="15" />{{ task === 'DEPENDENCY_IMPACT' ? '受影响服务与依赖变更' : '相关修复提交与匹配原因' }}</li><li><AppIcon name="check" :size="15" />{{ task === 'DEPENDENCY_IMPACT' ? '可核对的影响传播路径' : '部署版本的包含关系' }}</li><li><AppIcon name="check" :size="15" />代码原文与明确的结论边界</li></ul></div><RouterLink class="text-link" to="/scope">查看使用指南<AppIcon name="chevron" :size="14" /></RouterLink></aside>
    </div>
    <div ref="resultElement" class="result-anchor"><AnalysisResultPanel v-if="result" :key="result.id" :result="result" :cancelling="cancelling" @cancel="cancel" /><section v-else class="result-placeholder"><h2>分析结果</h2><p>提交问题后，结论和代码证据会显示在这里。</p><span><AppIcon name="shield" :size="16" />没有证据时，Agent 会明确说明，不猜测答案。</span></section></div>
  </template>
</template>
