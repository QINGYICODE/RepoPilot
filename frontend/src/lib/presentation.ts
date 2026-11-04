import { ApiError, request, type Snapshot } from '../api/repository'

export const EXAMPLES = {
  DEPENDENCY_IMPACT: {
    title: 'Admin 升级会影响哪些服务？',
    description: '比较 Spring Boot Admin 3.4.1 与 4.0.2，查看实际影响。',
    question: 'spring-petclinic-admin-server 的依赖从 3.4.1 升级到 4.0.2，会影响哪些服务？请给出依赖路径和代码证据。',
    head: '597ad1fbfabadfef4ed8a1d475f7f34aa2666aab', base: '17cad88d533554f53c634cfa78211ea2f51b0a46', module: 'spring-petclinic-admin-server',
  },
  INCIDENT_FIX: {
    title: 'Zipkin 告警有没有历史修复？',
    description: '从真实提交的修改内容中查找 tracing 相关修复线索。',
    question: '出现 zipkin tracing 告警，请查找 spring-boot-starter-zipkin 相关的历史修复 commit；不能确认事故关联时，只提供有 diff 支持的候选。',
    head: '3858f9c630cf989bb6809a86edf47c2be78dc9f1', base: '', module: 'spring-petclinic-api-gateway',
  },
} as const
export const shortSha = (sha: string) => sha.slice(0, 7)
export const shortModule = (name: string) => name.replace(/^spring-petclinic-/, '')
export const evidenceType = (type: string) => ({ FILE: '代码', COMMIT: '提交说明', DIFF: '修改内容' })[type] ?? type
export function friendlyError(error: unknown) {
  const code = error instanceof Error ? error.message : String(error)
  const messages: Record<string, string> = {
    REPOSITORY_NOT_ALLOWED: '该仓库尚未获得访问授权。请在仓库管理中主动接入并授权，或由维护者配置允许范围。',
    REPOSITORY_NOT_FOUND: '仓库不存在，请重新选择。', SNAPSHOT_NOT_READY: '该代码版本尚未就绪，请重新导入或选择其他版本。',
    UNKNOWN_SERVICE_MODULE: '所选模块不属于当前代码版本，请重新选择模块。', ANALYSIS_QUEUE_FULL: '正在处理的任务较多，请稍后再试。',
    ANALYSIS_RUN_NOT_FOUND: '未找到这次分析。它可能已被清理，你可以重新发起分析。',
    RUN_OWNED_BY_ANOTHER_PROCESS: '这次任务在另一个后端进程中执行，请前往对应进程取消。',
    FULL_COMMIT_SHA_REQUIRED: '请输入完整的 40 位提交 SHA。', INVALID_ANALYSIS_REQUEST: '请补全问题和代码版本后重试。',
    INVALID_REPOSITORY_ADDRESS: '请输入 GitHub 仓库地址或 owner/repository，暂不支持其他 Git 服务或本地目录。',
    INVALID_REPOSITORY_NAME: '仓库名称格式不正确，请使用 owner/repository 或完整 GitHub 地址。',
    GITHUB_REPOSITORY_NOT_FOUND: 'GitHub 未找到这个仓库。请检查地址是否存在；私有仓库也可能返回此结果。',
    GITHUB_PRIVATE_REPOSITORY_UNSUPPORTED: '当前仅支持公开仓库，尚未开放私有仓库接入。',
    GITHUB_RATE_LIMITED: 'GitHub 访问额度暂时用尽，请等待额度恢复后重试；可在后端配置只读 GitHub Token。',
    GITHUB_AUTH_FAILED: 'GitHub Token 无效或已过期，请更新服务端配置后重试。',
    GITHUB_ACCESS_DENIED: 'GitHub 拒绝访问，请检查服务端 Token 的权限或网络访问策略。',
    GITHUB_UNAVAILABLE: '暂时无法连接 GitHub，请检查网络或 REPOSITORY_PROXY 配置后重试。',
    GITHUB_UPSTREAM_ERROR: 'GitHub 服务暂时异常，请稍后重试。',
    GITHUB_REPOSITORY_MOVED: '仓库可能已更名或转移，请复制当前 GitHub 地址重新接入。',
    GITHUB_EMPTY_REPOSITORY: '仓库已接入，但默认分支还没有可导入的提交。请先在 GitHub 提交代码。',
    GITHUB_IDENTITY_CHANGED: '该地址对应的仓库身份发生变化，已停止读取，请核对仓库地址。',
    GITHUB_IDENTITY_MISMATCH: 'GitHub 返回的仓库身份与请求不符，已停止接入。',
    GIT_FETCH_FAILED: '代码拉取失败。可重试导入；已完成的索引和原有分析不会被覆盖。',
    GIT_AUTH_FAILED: 'Git 传输认证失败，请检查服务端 GitHub Token 配置。',
    GIT_ACCESS_DENIED_OR_RATE_LIMITED: 'GitHub 暂时拒绝代码传输，请检查权限或等待限流恢复后重试导入。',
    GIT_DEFAULT_BRANCH_NOT_FOUND: 'GitHub 默认分支未找到，请刷新仓库信息后重新导入。',
    UNKNOWN_COMMIT: '没有获取到这个提交，请确认完整 SHA 属于当前仓库的默认分支历史。',
    COMMIT_NOT_ON_ALLOWED_REF: '这个提交不在当前允许分支的历史中，不能导入。',
    CONNECTION_INTERRUPTED: '服务重启中断了接入，请重新接入。',
    CONNECTION_LEASE_EXPIRED: '接入任务中断且未能恢复，请重新接入。',
    CONNECTION_FAILED: '仓库接入未完成，请重试；已导入的代码不会被覆盖。',
    REPOSITORY_AUTHORIZATION_MANAGED_BY_CONFIG: '此仓库由服务端允许列表管理，请由维护者调整配置。',
  }
  if (messages[code]) return messages[code] + (error instanceof ApiError && error.retryAfterSeconds ? ` 建议在 ${Math.ceil(error.retryAfterSeconds / 60)} 分钟后重试。` : '')
  if (code.includes('fetch') || code.includes('Network') || code.includes('Timeout') || code.includes('aborted')) return '暂时无法连接服务，请检查本机后端是否启动后重试。'
  return `操作未完成（${code}），请重试。`
}
export function warningGroups(warnings: string[]) {
  const groups = new Set<string>()
  for (const warning of warnings) {
    if (/BOM|PARENT|UNRESOLVED|PROFILE|OPAQUE/.test(warning)) groups.add('部分外部依赖、父配置或 profile 尚未完整解析。')
    else if (/RUNTIME|CALL_GRAPH/.test(warning)) groups.add('未分析运行时服务调用，结果反映代码声明中的影响。')
    else if (/TRUNCATED|LIMIT|SIZE/.test(warning)) groups.add('部分检索或证据达到范围上限，结果可能不完整。')
    else if (/SIMILARITY|CONFIRMED|LEXICAL/.test(warning)) groups.add('历史检索基于日志特征匹配，相似修复不代表已确认的事故关联。')
    else if (/REVERT|EQUIVALENT/.test(warning)) groups.add('尚未核验等价补丁及后续回滚，应用修复前仍需人工复核。')
    else if (/STOPPED/.test(warning)) groups.add('本次分析提前停止，仅展示停止前已核验的结果。')
    else groups.add('存在尚未核验的项目，完整记录可在技术详情中查看。')
  }
  return [...groups]
}

export interface CommitLabel { subject: string; parents: string[] }
const labels = new Map<string, Promise<CommitLabel>>()
export function commitLabel(repoId: number, snapshot: Snapshot): Promise<CommitLabel> {
  const key = `${repoId}:${snapshot.id}`
  const cached = labels.get(key)
  if (cached) return cached
  const params = new URLSearchParams({ q: snapshot.commitSha, sourceSha: snapshot.commitSha, type: 'COMMIT', limit: '1' })
  const pending = request<{ items: { excerpt: string; locator: { parents?: string[] } }[] }>(`/repositories/${repoId}/snapshots/${snapshot.id}/search?${params}`)
    .then(page => ({ subject: page.items[0]?.excerpt.split('\n')[0]?.trim() ?? '', parents: page.items[0]?.locator.parents ?? [] }))
    .catch(() => { labels.delete(key); return { subject: '', parents: [] } })
  labels.set(key, pending)
  return pending
}

export async function loadCommitLabels(repoId: number, snapshots: Snapshot[]) {
  const result: Record<number, CommitLabel> = {}
  let next = 0
  await Promise.all(Array.from({ length: Math.min(3, snapshots.length) }, async () => {
    while (next < snapshots.length) { const snapshot = snapshots[next++]!; result[snapshot.id] = await commitLabel(repoId, snapshot) }
  }))
  return result
}
