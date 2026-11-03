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

## 当前快照范围

D2 快照记录固定 tree SHA、文件数和同步状态，coverage 明确为 METADATA_ONLY。GET /repositories/{repoId}/snapshots/{snapshotId}/tree 返回该固定提交的受限文件清单，不执行仓库代码；此提交不把元数据快照当作已建立代码或历史索引。

配置 REPOSITORY_ALLOWLIST、REPOSITORY_STORAGE_DIR、GITHUB_TOKEN 与可选 REPOSITORY_PROXY。代理仅影响 GitHub API 与 JGit；服务默认只绑定本机。
