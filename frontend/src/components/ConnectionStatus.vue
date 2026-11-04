<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { ApiError, type ConnectionJob, type RepositoryAccessSettings, type SyncJob } from '../api/repository'
import { friendlyError, shortSha } from '../lib/presentation'
import AppIcon from './AppIcon.vue'
const props = defineProps<{ connection: ConnectionJob; settings: RepositoryAccessSettings | null; syncJob: SyncJob | null; retrying: boolean }>()
const emit = defineEmits<{ retry: []; reread: [] }>()
const now = ref(Date.now()), interval = setInterval(() => { now.value = Date.now() }, 1000)
onUnmounted(() => clearInterval(interval))
const wait = computed(() => props.connection.retryAt ? Math.max(0, Math.ceil((Date.parse(props.connection.retryAt) - now.value) / 1000)) : 0)
const imported = computed(() => props.syncJob?.id === props.connection.syncJobId ? props.syncJob : null)
const failure = computed(() => props.connection.status === 'FAILED' ? friendlyError(new ApiError(props.connection.errorCode ?? 'CONNECTION_FAILED')) : imported.value?.status === 'FAILED' ? friendlyError(new ApiError(imported.value.errorCode ?? 'GIT_FETCH_FAILED')) : '')
const title = computed(() => {
  if (failure.value) return props.connection.repoId ? '仓库已接入，版本导入尚未完成' : '仓库接入未完成'
  if (props.connection.status === 'READY') return imported.value?.status === 'READY' ? '仓库与代码版本已就绪' : props.connection.syncJobId ? '仓库已接入，正在导入代码版本' : '仓库已接入'
  return ({ QUEUED: '等待连接 GitHub', VERIFYING: '正在核验公开仓库信息', RESOLVING: '正在读取默认分支的提交' } as Record<string,string>)[props.connection.status] ?? '正在接入'
})
</script>
<template>
  <section class="connection-status" :class="{ 'has-error': !!failure }" aria-live="polite" data-testid="connection-status" :data-status="connection.status">
    <div class="connection-heading"><AppIcon :name="failure ? 'warning' : connection.status === 'READY' ? 'check' : 'activity'" /><div><h3>{{ title }}</h3><p>{{ connection.owner }}/{{ connection.name }}<template v-if="imported"> · {{ shortSha(imported.targetSha) }} · {{ ({ QUEUED: '等待导入', FETCHING: '正在拉取代码', INDEXING: '正在建立索引', READY: '索引就绪', FAILED: '导入失败' } as Record<string,string>)[imported.status] ?? imported.status }}</template></p></div></div>
    <p v-if="failure" class="connection-error">{{ failure }}</p>
    <p v-if="wait" class="field-help">GitHub 建议在 {{ new Date(connection.retryAt!).toLocaleTimeString('zh-CN') }} 后重试（约 {{ Math.ceil(wait / 60) }} 分钟）。</p>
    <div class="connection-actions"><button v-if="connection.status === 'FAILED'" type="button" class="button secondary" :disabled="wait > 0 || retrying" @click="emit('retry')">{{ retrying ? '正在重试' : wait > 0 ? '等待 GitHub 额度恢复' : '重新接入' }}</button><button v-if="imported?.status === 'FAILED'" type="button" class="button secondary" :disabled="retrying" @click="emit('retry')">重新接入并导入</button><button v-if="connection.status === 'READY' && imported?.status === 'READY'" type="button" class="text-button" @click="emit('reread')">刷新仓库版本<AppIcon name="refresh" :size="14" /></button></div>
    <details class="technical-warnings"><summary>接入诊断信息</summary><dl class="execution-facts"><div><dt>连接尝试</dt><dd>{{ connection.attempts }} 次</dd></div><div><dt>网络方式</dt><dd>{{ settings?.networkMode === 'EXPLICIT_PROXY' ? '管理员指定代理' : settings?.networkMode === 'DIRECT_THEN_ENVIRONMENT_PROXY' ? '直连优先，网络故障时尝试环境代理' : '直连' }}</dd></div><div><dt>GitHub Token</dt><dd>{{ settings?.githubTokenConfigured ? '已配置' : '未配置（匿名访问）' }}</dd></div><div v-if="connection.errorCode"><dt>原因标记</dt><dd><code>{{ connection.errorCode }}</code></dd></div></dl></details>
  </section>
</template>
