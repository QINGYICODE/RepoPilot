# RepoPilot：面向单个代码库的运维分析 Agent

RepoPilot 为研发和值班人员提供两类有证据的分析：**依赖变更影响范围**和**告警历史修复线索**。每次分析绑定一个仓库快照，返回可定位到文件、行号和 commit 的依据。

## 文档入口

- [项目文档](docs/项目文档.md)：业务边界、技术选型、数据与工具设计、Agent 循环、证据拒答、接口、验收与排期。
- [开发记录](docs/开发记录.md)：本次已完成的调研和设计，以及待实施的开发任务、验收要求与记录模板。

## 技术路线

Vue 3 + TypeScript → Spring Boot 4 + Spring AI 2 → MySQL + MyBatis-Plus；工具层封装 GitHub REST API、JGit 和静态依赖分析器。

分析对象选用真实开源仓库 [spring-petclinic/spring-petclinic-microservices](https://github.com/spring-petclinic/spring-petclinic-microservices)。RepoPilot 是独立的分析平台，该上游仓库是被分析的数据来源。

## 首期范围

1. 注册允许访问的 GitHub 公共仓库，建立带 SHA 的代码、历史和依赖索引。
2. 比较两个 commit 的依赖声明，输出受影响服务、依赖路径和分析限制。
3. 输入服务名、异常日志与部署版本，检索历史修复候选，并核对相关 diff。
4. 展示证据引用、工具执行轨迹、分析覆盖范围和明确的拒答原因。

首期只读；代码库之外的问题、没有证据支持的根因或修复判断均不直接作答。

## 当前状态

2026-11-03：D1 工程已创建，包括基础工作台、数据库迁移、MyBatis-Plus Mapper、只读能力探针与分层验证入口。仓库同步、依赖分析和历史修复属于 D2 之后的任务；页面会明确显示计划状态。

D1 的实际验收结果与环境问题见[开发记录](docs/开发记录.md)。模拟工具响应、真实 SDK 协议验证和真实模型验证分别记录，不混为一个结果。

D1 验收已通过：本机 MySQL 8.0.45、真实模型 `qwen3.8-flash`、后端 11 项测试及前端构建。详见[验收记录](docs/verification/D1验收.md)和[后端依赖树](docs/verification/d1-dependency-tree.txt)。

## 本机启动

需要完整 JDK 21（`java -version` 与 `javac -version`）、Maven 3.9+、Node.js 22.12+、Python 3 和本机 `localhost:3306` 上的 MySQL 8+，推荐 MySQL 8.4。

1. 复制 `.env.example` 为 `.env`，填写已有 MySQL 账户的 `DB_USERNAME`、`DB_PASSWORD`；`DB_URL` 默认为 `localhost:3306/repopilot`。已有 `.env` 请保留并修改，不要覆盖。
2. 用有权限的本机 MySQL 账户执行 `scripts/init-local.sql`，或自行创建空的 `repopilot` 数据库。应用启动时由 Flyway 创建表；无需 Docker。
3. 在项目根目录启动后端：

```bash
python3 scripts/dev.py backend
```

4. 在另一个终端启动前端：

```bash
cd frontend
npm ci --ignore-scripts
npm run dev
```

访问 `http://127.0.0.1:5173`。前端 `/api` 代理到 `http://127.0.0.1:8080`；工作台从真实后端读取连接状态和版本。后端默认只绑定本机地址。

`.env` 按数据加载，不会当作 Shell 执行；JDBC URL 中的 `&` 无需转义。已有环境变量优先于 `.env`。如果 Maven 本地缓存需要另设位置，可指定 `MAVEN_REPO_LOCAL`；完整 JDK 可通过 `JAVA_HOME` 指定。

## 验证

```bash
# 基础验证：编译、7 项工具探针单元测试、打包；不连接 MySQL/真实模型
# 首次构建仍需联网下载依赖
python3 scripts/dev.py backend-check

# 本机 MySQL：迁移、增查改删、HTTP 能力/健康接口，以及本地模拟服务的真实 SDK 往返
python3 scripts/dev.py mysql-test

# 显式调用真实模型：先在 .env 配置 LLM_BASE_URL、LLM_MODEL、LLM_API_KEY
python3 scripts/dev.py live-llm

# 输出实际 Maven 依赖树至 backend/target/dependency-tree.txt
python3 scripts/dev.py dependency-tree

cd frontend
npm run build
```

`mysql-test` 只增删带随机测试标识的仓库记录，保留 Flyway 表和业务表；请连接专用 `repopilot` 数据库。真实模型检查最多请求两次模型、执行一次只读工具，并核验模型 JSON 与实际工具结果一致。普通后端启动不会自动发起模型调用，真实模型检查可能产生供应商费用。

`LLM_BASE_URL` 填写供应商的完整 API 前缀，例如 `https://api.openai.com/v1`；SDK 在其后追加 `/chat/completions`，本地协议测试核对该路径。API Key 仅供后端读取，不能写入 `VITE_*`、提交记录或前端源码。

GitHub Actions 执行后端单元验证与前端构建，不配置生产数据库或模型密钥；MySQL 与真实模型验证需要显式在本机执行。
