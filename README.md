# RepoPilot

**基于仓库证据的代码运维分析 Agent。**

RepoPilot 面向微服务研发、代码评审和值班排障，聚焦依赖变更影响与历史修复检索。项目以明确的代码仓库和版本为分析边界，通过工具收集代码、配置和提交记录，为分析结论提供可复核的依据。

项目提供仓库白名单登记、GitHub 身份校验、固定 SHA 同步、异步任务和快照元数据查询。后端使用 Spring Boot、MySQL 和 Spring AI，接口说明见 [API 文档](docs/API.md)。

## 应用场景

| 场景 | 典型问题 | 分析关注点 |
| --- | --- | --- |
| 依赖变更评审 | “这个模块的依赖变更会影响哪些服务？” | 版本差异、依赖使用方、构建影响与潜在调用影响 |
| 告警历史溯源 | “某个告警对应的历史修复 commit 是什么？” | 异常与服务定位、历史候选、实际 diff 和部署适用性 |

项目选用 [Spring PetClinic Microservices](https://github.com/spring-petclinic/spring-petclinic-microservices) 作为仓库分析对象，其 Maven 多模块结构和服务配置可用于研究依赖关系与运维问题。

## 设计原则

- **明确分析范围**：一次任务绑定一个仓库与指定版本，避免混用不同快照的内容。
- **结论关联证据**：用文件、行号、完整 commit SHA 和 diff 支撑分析，区分事实、推断与未知项。
- **控制工具循环**：限制调用次数、上下文和输出规模，保留任务约束与关键工具结果。
- **无证据拒答**：证据不足时说明缺失信息；相似修复候选与已确认的告警关联分别表述。
- **保持只读边界**：围绕代码和历史记录开展分析，不执行发版或生产变更。

## 技术栈

| 层次 | 技术 |
| --- | --- |
| 前端 | Vue 3、TypeScript、Vite、Pinia、Vue Router |
| 后端 | Java 21、Spring Boot 4、Spring MVC |
| Agent 编排 | Spring AI 2 |
| 数据持久化 | MySQL、MyBatis-Plus、Flyway |
| 测试与观测 | JUnit、Actuator、Micrometer |

仓库层使用 GitHub API 和 JGit 绑定允许的仓库与明确版本，不执行目标仓库脚本。

## 快速开始

需要完整 JDK 21、Maven 3.9+、Node.js 22.12+、Python 3，以及本机 `localhost:3306` 上的 MySQL 8+。

### 配置数据库

首次运行时，将 `.env.example` 复制为 `.env`，填写 MySQL 账户的 `DB_USERNAME` 和 `DB_PASSWORD`。已有 `.env` 直接修改即可。

`DB_URL` 默认连接 `localhost:3306/repopilot`。使用有权限的本机账户执行 [初始化 SQL](scripts/init-local.sql) 创建数据库，应用启动时由 Flyway 创建表。

### 启动服务

在项目根目录启动后端：

```bash
python3 scripts/dev.py backend
```

在另一个终端启动前端：

```bash
cd frontend
npm ci --ignore-scripts
npm run dev
```

访问 `http://127.0.0.1:5173`。前端通过 `/api` 代理访问 `http://127.0.0.1:8080`，工作台读取后端的实际服务状态。

### 连接模型

在 `.env` 中配置 `LLM_BASE_URL`、`LLM_MODEL` 和 `LLM_API_KEY`，然后在项目根目录执行只读工具调用验证：

```bash
python3 scripts/dev.py live-llm
```

`LLM_BASE_URL` 使用供应商的完整 API 前缀，例如 `https://api.openai.com/v1`。模型密钥仅由后端读取；`.env` 不纳入版本控制。

## 项目结构

```text
backend/    Spring Boot 服务、数据库迁移与工具调用验证
frontend/   Vue 工作台
scripts/    本机启动、验证与数据库初始化脚本
docs/       项目设计、开发记录与验证资料
```

## 相关文档

- [项目文档](docs/项目文档.md)
- [开发记录](docs/开发记录.md)
