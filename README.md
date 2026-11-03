# RepoPilot

**基于仓库证据的代码运维分析 Agent。**

RepoPilot 面向微服务研发、代码评审和值班排障，聚焦依赖变更影响与历史修复检索。项目以明确的代码仓库和版本为分析边界，通过工具收集代码、配置和提交记录，为分析结论提供可复核的依据。

接入真实 GitHub 仓库后，选择模块和版本并输入问题，Agent 通过 Spring AI 调用 JGit 历史检索与依赖分析工具，返回受影响服务、历史修复候选和可核对的原文证据。Vue 页面可查看工具执行记录、分析结论、版本范围与拒答原因，并支持取消任务和刷新恢复结果。

- **依赖变更影响**：比较两个固定版本，沿 Maven 声明依赖追踪受影响服务，展示变更与依赖路径。
- **告警历史修复**：从异常、日志符号或配置键检索真实提交与修改行，返回完整 commit SHA，并核对部署版本是否已经包含该提交。
- **范围与证据约束**：仓库白名单、固定快照和只读工具由服务端控制；越界、越权、伪造引用和无证据结论会被拒绝。
- **有界工具循环**：控制模型次数、工具次数、时间与上下文规模，缓存重复调用并保留执行记录。

## 应用场景

| 场景 | 典型问题 | 分析关注点 |
| --- | --- | --- |
| 依赖变更评审 | “这个模块的依赖变更会影响哪些服务？” | 版本差异、依赖使用方、构建影响与潜在调用影响 |
| 告警历史溯源 | “某个告警对应的历史修复 commit 是什么？” | 根据实际 diff 提供相似候选；缺少明确事故关联时不声称确定修复 |

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

工具层使用 GitHub API、JGit、JavaParser 和静态 Maven 解析器组织证据，由应用负责仓库白名单、固定快照和结果核验。依赖分析覆盖声明使用关系，外部 BOM、profile 与运行时调用的限制会随结果返回。

## 快速开始

需要完整 JDK 21、Maven 3.9+、Node.js 22.12+、Python 3，以及本机 `localhost:3306` 上的 MySQL 8+。

### 配置数据库

首次运行时，将 `.env.example` 复制为 `.env`，填写 MySQL 账户的 `DB_USERNAME` 和 `DB_PASSWORD`。已有 `.env` 直接修改即可。

`DB_URL` 默认连接 `localhost:3306/repopilot`。使用有权限的本机账户执行 [初始化 SQL](scripts/init-local.sql) 创建数据库，应用启动时由 Flyway 创建表。

### 启动服务

在 `.env` 配置 `LLM_BASE_URL`、`LLM_MODEL`、`LLM_API_KEY` 后，在项目根目录启动 Agent 后端：

```bash
python3 scripts/dev.py agent
```

在另一个终端启动前端：

```bash
cd frontend
npm ci --ignore-scripts
npm run dev
```

访问 `http://127.0.0.1:5173`，首页为“提问与分析”。前端通过 `/api` 代理访问 `http://127.0.0.1:8080`。

在“仓库与证据”页登记允许访问的 GitHub 仓库，输入完整 commit SHA 建立索引；回到首页选择任务、模块及版本并提问。依赖比较需要两个快照；告警分析可以补充完整部署 SHA。允许列表和可选网络代理通过 `.env.example` 配置，接口用法见 [API 文档](docs/API.md)。

暂不配置模型时，可用 `python3 scripts/dev.py backend` 启动仓库同步、检索和依赖工具；Agent 任务会明确提示模型未启用。WSL 挂载目录无法自动刷新时，前端可使用 `CHOKIDAR_USEPOLLING=true npm run dev`。

### 模型与业务验证

`LLM_BASE_URL` 使用供应商的完整兼容 API 前缀，例如 `https://api.openai.com/v1`。模型密钥仅由后端读取；`.env` 不纳入版本控制。

可在项目根目录验证模型工具协议：

```bash
python3 scripts/dev.py live-llm
```

导入验证说明中的真实快照后，可执行 `python3 scripts/dev.py agent-check`，验证依赖分析、告警候选与拒答业务链路，详见 [业务验证记录](docs/verification/D5-D8验收.md)。

项目目前面向本机单用户使用，权限边界是服务端仓库允许列表和任务范围。声明依赖影响不等同于运行时故障；候选未按 SHA 合入也不排除等价补丁或后续回滚。

## 项目结构

```text
backend/    Spring Boot 服务、Spring AI 编排、仓库工具与数据库迁移
frontend/   Vue 分析入口、执行记录与证据工作台
scripts/    本机启动、验证与数据库初始化脚本
docs/       项目设计、开发记录与验证资料
```

## 相关文档

- [项目文档](docs/项目文档.md)
- [API 文档](docs/API.md)
- [开发记录](docs/开发记录.md)
