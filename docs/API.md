# RepoPilot API

服务默认地址为 `http://127.0.0.1:8080/api`，前端开发环境通过 `/api` 代理访问。管理接口仅用于本机工作台；当前没有面向公网的用户登录与多租户授权。

所有仓库操作都经过服务端允许列表校验。请求只接受 owner/name，不能传任意下载 URL、磁盘目录或 Git 远程地址。同步只接受允许分支可达的完整 40 位 commit SHA。

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
```

真实上游和真实模型验证均需显式选择。日志保存在被忽略的本机目录中，公共开发记录不包含凭据。当前同步工作池按单个后端进程设计，不应由多个实例共享同一镜像目录。

## 错误约定

请求参数错误返回 400，仓库不在允许范围内返回 403，资源不存在返回 404，快照未就绪或证据哈希不一致返回 409。响应为 `{"code":"..."}`。

GitHub 限流、认证失败与网络不可用分别保留错误码；异步失败写入任务记录，不会被解释为“没有代码或历史证据”。
