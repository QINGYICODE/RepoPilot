# RepoPilot API

服务默认地址为 `http://127.0.0.1:8080/api`，前端开发环境通过 `/api` 代理访问。管理接口仅用于本机工作台；当前没有面向公网的用户登录与多租户授权。

所有仓库读取与分析都经过服务端授权校验（预设允许列表或管理页面显式授权）。接入管理接口可解析公开 GitHub 地址，下载目标始终由服务端固定生成；不接受其他主机、磁盘目录或任意 Git 远程地址。同步只接受允许分支可达的完整 40 位 commit SHA。

## 接入公开仓库

`POST /repository-connections` 返回 HTTP 202，立即建立可恢复的连接任务：

```json
{
  "address": "https://github.com/QINGYICODE/RepoPilot",
  "authorizeAccess": true,
  "autoImport": true
}
```

`address` 支持 `owner/name`、GitHub HTTPS 页面地址（含子路径、查询参数）、HTTPS `.git` 地址和 `git@github.com:owner/name.git`。归一化后仅向固定的 GitHub 主机请求。`authorizeAccess=true` 是本机管理操作，核验公开属性、仓库名称及稳定 githubId 后才写入授权；`false` 仅接入已授权范围。`ALLOW_MANUAL_REPOSITORY_AUTHORIZATION=false` 关闭额外授权。此接口不注册为模型工具。

`GET /repository-connections/{id}` 返回 `id / owner / name / status / attempts / repoId / syncJobId / errorCode / retryAt` 等字段。连接状态为 `QUEUED → VERIFYING → RESOLVING → READY` 或 `FAILED`，同一仓库与选项的进行中任务去重。进程租约过期可恢复，停止只中断本实例任务。`READY` 表示连接完成；自动导入时需另查 `syncJobId`，直至索引也为 READY。解析默认提交失败时可能已有 `repoId`，不会误报为全部接入失败。

`POST /repository-connections/{id}/retry` 重试失败连接，或重试连接已完成但索引失败的任务。重试保留原连接记录；限流等待期内返回 `GITHUB_RATE_LIMITED`，包含 `Retry-After` 响应头和 `retryAfterSeconds` 字段。GitHub API 每次操作总预算 18 秒，单次请求最多 6 秒，网络/5xx 最多三次；401、404、私有仓库和限流不盲目重试。未显式指定代理时仅传输故障允许转向环境代理，不因限流/权限错误更换出口。

`GET /repository-connections/settings` 返回手动授权开关、预设范围、Token 是否配置及网络模式，不返回密钥或代理地址。

`DELETE /repositories/{id}/authorization` 撤销动态授权，返回 204；保留本机镜像和索引，后续读取、检索、分析均重新校验权限。预设仓库返回 `REPOSITORY_AUTHORIZATION_MANAGED_BY_CONFIG`，需调整服务端配置。

## 登记与同步

### 登记仓库

`POST /repositories`

```json
{
  "owner": "spring-petclinic",
  "name": "spring-petclinic-microservices"
}
```

返回仓库 `id`、`githubId`、`defaultRef` 和 `activeSnapshotId`。重复登记返回同一个仓库，不创建重复记录。首次登记通过 GitHub API 核对真实身份和公开可见性。

`GET /repositories` 返回允许范围内的已登记仓库。

### 创建同步任务

`POST /repositories/{repoId}/sync-jobs`

```json
{
  "commitSha": "3858f9c630cf989bb6809a86edf47c2be78dc9f1"
}
```

返回 HTTP 202 和任务对象；通过 `GET /sync-jobs/{jobId}` 查询。

状态为 `QUEUED → FETCHING → INDEXING → READY`，失败为 `FAILED`，并返回脱敏后的 `errorCode`。同一仓库、SHA、索引版本与配置指纹的任务幂等；重新提交失败任务会复用 jobId 并增加实际执行次数。

同步使用受控 bare Git 镜像，不 checkout 或执行目标仓库代码，不递归下载子模块。失败或未完成的索引不会替换已就绪的快照。服务重启后，过期租约任务可恢复入队；连续恢复失败达到限制时保持 FAILED，等待显式重试。

### 快照列表

`GET /repositories/{repoId}/snapshots`

仅返回 READY 快照。每条记录包含完整 SHA、索引版本、`coverage` 和 `stats`。

`coverage.historyComplete` 仅描述是否遍历完该提交的可达历史，不代表所有文件、diff 或 Maven 模型都完整。`coverage.warnings` 会分别列出文件大小、历史数量、证据数量、diff 截断、未解析依赖与 profile 等限制。

当前 `stats.hunkCount` 统计 DIFF 证据条数，包含 metadataOnly 记录；`durationMs` 为索引构建阶段，不含远程下载和数据库发布。

## 固定版本的文件与证据

### 文件清单与内容

- `GET /repositories/{repoId}/snapshots/{snapshotId}/files`：已索引文件清单。
- `GET /repositories/{repoId}/snapshots/{snapshotId}/files?path=pom.xml&startLine=1&endLine=40`：读取指定文件范围。

单次最多 200 行；响应包含 `sourceSha`、实际行范围、全文行数、片段哈希及是否仅返回部分文件。禁止绝对路径、`..`、反斜杠和 `.git` 路径。

### 检索

`GET /repositories/{repoId}/snapshots/{snapshotId}/search`

| 参数 | 含义 |
| --- | --- |
| `q` | 必填，1～200 字符；异常、符号、路径、SHA 或文本 |
| `type` | 可选：FILE、COMMIT、DIFF |
| `path` | 可选，完整仓库文件路径精确过滤 |
| `symbol` | 可选，完整符号精确过滤，例如 `example.Demo#fail()` |
| `sourceSha` | 可选，证据所属完整提交号 |
| `module` | 可选，模块目录过滤 |
| `limit` | 每页 1～100 条，默认 20 |
| `cursor` | 上一页返回的 nextCursor，绑定快照与全部查询条件 |

优先返回字面匹配；无字面匹配时使用 MySQL 英文全文索引和 ngram 全文索引回退。分页游标保留检索方式，不会在后续页切换查询语义。`retrievalMethod` 为 EXACT 或 MYSQL_FULLTEXT，不表示已完成语义相关性核验。

返回结构为 `items / nextCursor / truncated / coverage / retrievalMethod`。全文命中只是检索线索，不等于修复成立。

### 证据详情

`GET /repositories/{repoId}/snapshots/{snapshotId}/evidence/{evidenceId}`

证据必须属于该仓库和该快照的知识范围。返回前核对展示片段的内容哈希。

FILE 保留实际文件行号和固定 SHA 的 GitHub blob 链接。DIFF 保留 `parentSha`、`oldPath/newPath`、新旧起始行与行数，以及实际 patch hunk；纯重命名或模式变化通过 `metadataOnly` 标记。COMMIT 保留提交说明和父提交列表。

Java 按类型/方法和补充窗口切分；POM 按依赖、属性、管理声明等节点切分；YAML 保留配置块。较长内容会窗口化或显式截断，完整文件可按行继续读取。已知 Token、带引号的敏感赋值与私钥做规则脱敏，保留行数；哈希对应脱敏后的展示内容。

历史证据的模块目录用于静态路径预筛选，不能单独作为历史服务归属的证明；模块移动等情况需结合实际 commit 和 diff 核对。

## 依赖变更影响

`GET /repositories/{repoId}/snapshots/{snapshotId}/dependency-graph` 返回快照的静态 Maven 图与未知项。

`POST /repositories/{repoId}/dependency-impact`

```json
{
  "baseSnapshotId": 1,
  "headSnapshotId": 2,
  "module": "spring-petclinic-api-gateway",
  "maxDepth": 8
}
```

示例中的快照 ID 需要替换为实际返回值。`module` 可省略，表示比较全部模块；`maxDepth` 范围为 1～12。

结果包含：

- `changes`：实际消费依赖的增删、版本、scope、optional 和 exclusions 变化。
- `impacts`：服务、BASE/HEAD 视图、scope、路径及 evidenceIds。
- `affectedServices`：已确认声明影响的服务集合。
- `warnings`：未解析模型、BOM 变化、静态调用图缺失、深度/数量限制等。

删除依赖从 BASE 图追踪，新增依赖从 HEAD 图追踪；修改分别查询两图，禁止跨版本拼接路径。测试、provided 和 optional 关系不会作为普通传递依赖扩散，排除声明会阻断相应路径。dependencyManagement 本身不等于实际使用。

当前实现覆盖本地 parent、properties、版本管理和模块消费关系。外部 parent/BOM、profile、完整 Maven 传递版本仲裁、插件依赖及运行时 HTTP 调用不做完整解析，因此结果为声明层面的评审与回归范围，不能解释成“升级一定故障”或“其他服务完全不受影响”。

## 告警历史修复候选

`POST /repositories/{repoId}/fix-candidates`

```json
{"snapshotId": 29, "service": "spring-petclinic-api-gateway", "message": "spring-boot-starter-zipkin tracing", "deployedSha": null}
```

快照 ID 和模块路径需使用实际数据。`service`、`deployedSha` 可省略；告警正文最多 8,000 字符。当前从日志抽取最多六个英文异常、符号和配置词，在固定快照的 COMMIT/DIFF 中做有界字面检索；纯中文且无可定位符号可能无法召回。

候选必须同时存在修复语义的提交说明和包含匹配词的实际增删行；只有标题、上下文行或纯重命名不会被认作候选。最多返回三个 `RELATED_CANDIDATE`，包含完整 SHA、实际证据和匹配词。无候选返回 `REFUSED / NO_EVIDENCE`，有候选返回 `PARTIAL / CANDIDATE_ONLY`；均不声称已确认该告警与提交的对应关系。

提供部署 SHA 时，JGit 只读本地镜像，先验证它属于知识版本的祖先，再检查候选是否已在部署祖先中。结果不代表完成 cherry-pick 等价补丁、后续 revert 或运行时适用性验证；查询截断和索引覆盖限制随响应返回。此接口不会触发网络拉取。

## Agent 分析任务

配置模型并执行 `python3 scripts/dev.py agent` 启用真实模型。普通 `backend` 模式仍可访问仓库工具，分析任务会明确返回 `UNAVAILABLE / MODEL_NOT_CONFIGURED`。

`POST /analysis-runs` 返回 HTTP 202，随后通过 `GET /analysis-runs/{id}` 获取执行记录与终态。依赖分析示例：

```json
{"repoId": 9, "snapshotId": 29, "baseSnapshotId": 28, "task": "DEPENDENCY_IMPACT", "module": "spring-petclinic-api-gateway", "question": "依赖变更会影响哪些服务？"}
```

告警分析使用 `task: INCIDENT_FIX`，无需 baseSnapshotId，question 放告警/日志内容，可提供 module 和 deployedSha。所有 ID 都需替换为已授权仓库的实际值。

状态为 `RUNNING / ANSWERED / PARTIAL / REFUSED / NEEDS_INPUT / UNAVAILABLE / CANCELLED`。仓库白名单和快照所属关系在接收任务时核验；不提供登录或多用户 RBAC。未知仓库、越权资源使用既有 HTTP 403/404/409 错误约定。任务内的越界问题、模型越权工具请求和伪造引用使用明确拒答终态。

结果含 `summary / dependency / incident / citations / events / usage / reasonCode / warnings`。工具事件只记录名称、状态、错误码、缓存与耗时，不记录密钥或模型私有推理。只有经过业务工具与证据核验的结构化事实才能展示为结论；模型自由生成的答案正文不会直接展示。

每次最多 8 次模型请求、12 次工具请求、单工具 4 次请求和 90 秒任务期限；单次模型等待最多 30 秒。重复成功调用复用缓存，连续两次无进展停止；工具错误与无命中分别记录。业务工具已经核验证据后进入 FINALIZE 阶段，关闭后续工具，仅生成动作与引用。任务队列有界，满载返回 429。

`POST /analysis-runs/{id}/cancel` 取消本实例任务，终态不可覆盖；若活跃任务归属另一进程返回 409 / RUN_OWNED_BY_ANOTHER_PROCESS。任务由实例每 15 秒续租，租约 45 秒；进程退出后仅过期任务会标记 PROCESS_RESTARTED，正常停机标记 PROCESS_STOPPED，均不隐式再次调用模型。

上下文固定保留任务、规则和版本，只保留最近两组完整工具往返；按 UTF-8 字节加 schema/协议余量保守估算 token 上界，单次输入上界 24,000、累计上界 60,000。过大输入直接停止，不裁掉约束。使用供应商可靠用量时记录实际输入/输出 token；否则明确 estimatedTokens。`model / promptVersion / knowledgeSha / baseSha` 可用于复核一次执行，未保存模型私有推理。

`usage` 区分模型次数、请求工具次数、实际执行、执行失败、参数/权限拦截和缓存命中。执行失败率应使用 `failedToolCalls / executedToolCalls`，同时单独报告拦截与缓存，避免更改分母制造改善。没有可靠供应商 token 用量时使用保守估算并标记 estimatedTokens。18%→4% 尚不是实测成果。

## 配置与验证

| 环境变量 | 作用 |
| --- | --- |
| `REPOSITORY_ALLOWLIST` | 允许的 owner/name，逗号分隔 |
| `REPOSITORY_STORAGE_DIR` | bare 镜像目录；默认后端工作目录下的 `.local/repositories` |
| `GITHUB_TOKEN` | 可选，仅由服务端用于 GitHub 只读访问 |
| `REPOSITORY_PROXY` | 可选，`http://host:port`，只影响 GitHub API 与 JGit，不带认证信息 |

默认限制为 5,000 个文本文件、单文件 256 KiB、单快照文本 25 MiB、2,000 个历史提交和 40,000 条证据目标上限。Maven 证明引用与提交元数据会保留必要记录，截断通过 coverage 和单条证据标记表达。改变索引限制会产生不同的配置指纹，避免误复用旧覆盖范围。

```bash
python3 scripts/dev.py backend-check  # 单元与构建验证
python3 scripts/dev.py mysql-test     # 本机数据库与本地 Git/HTTP 集成
python3 scripts/dev.py upstream-test  # 真实 PetClinic 固定版本与五组历史变更
python3 scripts/dev.py full-check     # 上述检查加真实模型能力探针
python3 scripts/dev.py agent-check    # 已导入真实快照上的两个模型业务任务与五项修复候选
```

真实上游和真实模型验证均需显式选择。日志保存在被忽略的本机目录中，公共开发记录不包含凭据。当前同步工作池按单个后端进程设计，不应由多个实例共享同一镜像目录。

## 错误约定

请求参数错误返回 400，仓库不在允许范围内返回 403，资源不存在返回 404，快照未就绪或证据哈希不一致返回 409。响应为 `{"code":"..."}`。

GitHub 限流、认证失败与网络不可用分别保留错误码；异步失败写入任务记录，不会被解释为“没有代码或历史证据”。
