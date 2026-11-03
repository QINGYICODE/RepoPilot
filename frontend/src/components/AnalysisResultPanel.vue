<script setup lang="ts">
import { computed, ref } from 'vue'
import type { AnalysisResult } from '../api/analysis'
import AppIcon from './AppIcon.vue'
import EvidenceDrawer from './EvidenceDrawer.vue'
import { shortModule, shortSha, warningGroups } from '../lib/presentation'
const props = defineProps<{ result: AnalysisResult; cancelling?: boolean }>()
const emit = defineEmits<{ cancel: [] }>()
const selectedId = ref<string | null>(null), allChanges = ref(false)
const busy = computed(() => props.result.status === 'RUNNING')
const finished = computed(() => ['ANSWERED', 'PARTIAL'].includes(props.result.status))
const changes = computed(() => props.result.dependency?.changes.slice(0, allChanges.value ? undefined : 5) ?? [])
const statuses: Record<string, string> = { RUNNING: '分析中', ANSWERED: '分析完成', PARTIAL: '部分结论已核验', REFUSED: '无法给出结论', NEEDS_INPUT: '需要补充信息', UNAVAILABLE: '服务暂不可用', CANCELLED: '已取消' }
const reasons: Record<string, { title: string; body: string; next: string }> = {
  NO_EVIDENCE: { title: '没有找到足够的代码证据', body: '已检查所选版本的仓库内容，但没有找到能支持答案的依据。', next: '补充异常类型、配置键或相关模块，或换一个代码版本再试。' },
  OUT_OF_SCOPE: { title: '这个问题超出了仓库分析范围', body: '这里只分析当前 GitHub 仓库的依赖影响和历史修复，不回答与代码库无关的问题。', next: '换成与当前仓库有关的问题，或从真实仓库示例开始。' },
  FORBIDDEN: { title: '请求超出了当前授权范围', body: '本次任务只允许读取所选仓库和版本，不能访问其他仓库、读取密钥或执行命令。', next: '选择已授权仓库，并提出只读分析问题。' },
  MODEL_NOT_CONFIGURED: { title: '分析模型尚未启用', body: '仓库工具仍可使用，但 Agent 需要后端连接模型才能分析问题。', next: '请在服务状态页检查模型配置，再重新发起分析。' },
  CANCELLED: { title: '这次分析已取消', body: '任务已停止，尚未完成核验的内容不会展示为结论。', next: '可以修改问题后重新分析。' },
}
const refusal = computed(() => reasons[props.result.reasonCode] ?? { title: '这次分析尚未取得可靠结论', body: props.result.summary, next: '请检查版本与问题范围后重试；详细原因保留在执行记录中。' })
const tools: Record<string, string> = { compareDependencyChanges: '比较依赖并追踪使用服务', searchFixCandidates: '检索修复提交并核对修改内容', searchRepository: '检索代码与历史记录', readEvidence: '核对原文证据' }
const applicability: Record<string, string> = { DEPLOYED_VERSION_UNKNOWN: '未提供部署版本，尚不能判断是否已包含此修复。', COMMIT_PRESENT_IN_DEPLOYED_ANCESTRY: '部署版本已包含这个提交，当前告警可能需要排查其他原因。', COMMIT_NOT_IN_DEPLOYED_ANCESTRY: '部署历史中未包含此 SHA；仍需核验等价补丁与后续回滚。' }
const groupedWarnings = computed(() => warningGroups(props.result.warnings))
function evidence(id?: string) { selectedId.value = id && props.result.citations.some(c => c.id === id) ? id : props.result.citations[0]?.id ?? null }
</script>
<template>
  <section class="result-panel" aria-labelledby="result-title" :aria-busy="busy" data-testid="analysis-result" :data-status="result.status" :data-reason="result.reasonCode">
    <header class="section-header"><h2 id="result-title">分析结果</h2><span class="status-label" :class="{ 'status-running': busy, 'status-warning': result.status === 'PARTIAL' }"><span class="status-dot" :class="{ 'is-online': finished }" />{{ statuses[result.status] ?? '分析已结束' }}</span></header>
    <div v-if="busy" class="running-state" role="status"><AppIcon name="activity" :size="24" /><div><h3>{{ result.events.length ? '正在整理并核验结论' : '正在读取仓库并选择分析工具' }}</h3><p>你可以留在这里查看进度，也可以稍后从“最近分析”继续查看。</p></div><button class="button secondary" :disabled="cancelling" @click="emit('cancel')"><AppIcon name="stop" :size="15" />{{ cancelling ? '正在取消' : '取消分析' }}</button></div>
    <template v-else-if="finished && result.dependency">
      <h3 class="answer-title">{{ result.dependency.affectedServices.length ? `${result.dependency.affectedServices.length} 个服务受到声明依赖影响` : '本次未识别到明确的服务影响' }}</h3><p class="answer-description">以下是需要关注的评审与回归范围，依据所选版本中的 Maven 依赖关系计算。静态依赖影响不代表运行时一定故障。</p>
      <div class="service-list"><div v-for="service in result.dependency.affectedServices" :key="service" class="service-row"><AppIcon name="branch" /><strong>{{ shortModule(service) }}</strong><span class="muted">{{ service }}</span></div></div>
      <div class="section-header subsection-header"><h3>发生了哪些依赖变化</h3><span class="muted">{{ result.dependency.changes.length }} 项变更</span></div>
      <div class="change-table"><div class="change-table-head"><span>依赖与模块</span><span>变更前</span><span>变更后</span></div><div v-for="change in changes" :key="change.id" class="change-row"><div><strong>{{ change.coordinate }}</strong><span class="muted">{{ shortModule(change.module) }}</span></div><code><span class="mobile-cell-label">变更前</span>{{ change.before?.version ?? '未声明' }}</code><code><span class="mobile-cell-label">变更后</span>{{ change.after?.version ?? '已移除' }}</code></div></div>
      <button v-if="result.dependency.changes.length > 5" class="text-button" :aria-expanded="allChanges" @click="allChanges = !allChanges">{{ allChanges ? '收起变更' : `展开全部 ${result.dependency.changes.length} 项变更` }}<AppIcon name="down" :size="14" /></button>
      <details v-if="result.dependency.impacts.length" class="disclosure"><summary>查看影响传播路径<span>{{ result.dependency.impacts.length }} 条</span></summary><div v-for="(impact, i) in result.dependency.impacts" :key="i" class="path-row"><div class="dependency-path"><template v-for="(part, index) in impact.path" :key="index"><AppIcon v-if="index" name="arrow" :size="14" /><code>{{ shortModule(part) }}</code></template></div><div class="row-between"><span class="muted">{{ impact.view === 'BASE' ? '变更前版本' : '变更后版本' }} · {{ impact.scope }}</span><button class="text-button" @click="evidence(impact.evidenceIds[0])">核对依据<AppIcon name="chevron" :size="14" /></button></div></div></details>
    </template>
    <template v-else-if="finished && result.incident">
      <h3 class="answer-title">找到 {{ result.incident.candidates.length }} 条历史修复线索</h3><p class="answer-description">这些提交与告警特征相关，且有实际修改内容支持；尚不能确认它们就是本次告警对应的确定修复。</p>
      <article v-for="candidate in result.incident.candidates" :key="candidate.commitSha" class="candidate-row"><div class="row-between"><span class="status-label status-warning">相似修复候选</span><a :href="`https://github.com/${result.repository}/commit/${candidate.commitSha}`" target="_blank" rel="noopener noreferrer" class="commit-link"><code>{{ shortSha(candidate.commitSha) }}</code><AppIcon name="external" :size="14" /></a></div><h4>{{ candidate.subject }}</h4><p>{{ applicability[candidate.applicability] ?? '部署适用性尚未核验。' }}</p><div class="row-between"><span class="muted">匹配特征：{{ candidate.matchedTerms.join('、') }}</span><button class="text-button" @click="evidence(candidate.evidence.find(e => result.citations.find(c => c.id === e.id)?.type === 'DIFF')?.id)">查看修改证据<AppIcon name="chevron" :size="14" /></button></div></article>
    </template>
    <div v-else class="refusal-state" role="status"><AppIcon :name="result.reasonCode === 'CANCELLED' ? 'stop' : 'shield'" :size="24" /><div><h3>{{ refusal.title }}</h3><p>{{ refusal.body }}</p><p class="muted">{{ refusal.next }}</p></div></div>
    <div v-if="finished && result.citations.length" class="evidence-summary"><span><AppIcon name="file" :size="16" />{{ result.citations.length }} 项原文证据已核验</span><button class="button secondary" @click="evidence()">查看原文证据<AppIcon name="chevron" :size="15" /></button></div>
    <details v-if="groupedWarnings.length && !busy" class="disclosure"><summary>结论的适用范围<span>{{ groupedWarnings.length }} 类未核验项</span></summary><ul class="plain-list"><li v-for="warning in groupedWarnings" :key="warning">{{ warning }}</li></ul></details>
    <details class="disclosure execution-details" :open="busy"><summary>执行记录与技术详情<span>{{ result.usage.modelCalls }} 次模型调用</span></summary><ol v-if="result.events.length" class="tool-events"><li v-for="event in result.events" :key="event.sequence"><AppIcon :name="event.status === 'ERROR' || event.status === 'REJECTED' ? 'warning' : 'check'" :size="16" /><span>{{ tools[event.tool] ?? '拦截未授权工具' }}</span><span class="muted">{{ event.cached ? '缓存复用' : `${event.durationMs} ms` }} · {{ event.status === 'EMPTY' ? '未命中' : event.status === 'OK' ? '完成' : '未完成' }}</span></li></ol><p v-else class="muted">{{ busy ? '等待工具返回结果。' : '此次任务未执行仓库工具。' }}</p><dl class="execution-facts"><div><dt>知识版本</dt><dd><code>{{ shortSha(result.knowledgeSha) }}</code></dd></div><div v-if="result.baseSha"><dt>比较基准</dt><dd><code>{{ shortSha(result.baseSha) }}</code></dd></div><div><dt>工具实际执行</dt><dd>{{ result.usage.executedToolCalls }} 次</dd></div><div><dt>执行失败 / 参数拦截</dt><dd>{{ result.usage.failedToolCalls }} / {{ result.usage.rejectedToolCalls }} 次</dd></div><div><dt>输入 / 输出 token</dt><dd>{{ result.usage.inputTokens.toLocaleString() }} / {{ result.usage.outputTokens.toLocaleString() }}{{ result.usage.estimatedTokens ? '（含估算）' : '' }}</dd></div><div><dt>已用时间</dt><dd>{{ (result.usage.durationMs / 1000).toFixed(1) }} 秒</dd></div><div><dt>终止原因</dt><dd><code>{{ result.reasonCode }}</code></dd></div></dl><details v-if="result.warnings.length" class="technical-warnings"><summary>完整范围标记（{{ result.warnings.length }}）</summary><ul><li v-for="warning in result.warnings" :key="warning"><code>{{ warning }}</code></li></ul></details></details>
    <EvidenceDrawer :citations="result.citations" :selected-id="selectedId" :repository="result.repository" @close="selectedId = null" @select="selectedId = $event" />
  </section>
</template>
