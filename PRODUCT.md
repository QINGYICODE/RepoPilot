# RepoPilot

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

需要评审依赖变更或排查代码告警的研发与运维人员。首次使用者需要能直接完成一次真实分析，而不先理解快照 ID、索引和 Agent 内部协议。

## Product Purpose

在明确授权的真实 GitHub 仓库和代码版本内，回答依赖变更影响哪些服务，以及某个告警有哪些历史修复线索。答案关联可复核的代码证据；超范围、越权或证据不足时明确拒答。

## Operating Context

当前为本机单用户工作台，连接 localhost:3306 MySQL。Vue 3 前端通过 /api 访问 Spring Boot 4 后端；Spring AI 编排只读工具，JGit、代码索引与 Maven 分析提供依据。

## Capabilities and Constraints

- 仓库、固定提交、模块和任务范围由服务端核验；不提供公网多租户登录。
- 依赖任务比较两个固定版本，输出声明依赖影响与传播路径，不等同于运行时故障。
- 告警任务检索真实提交和 diff，返回相似候选；没有事故关联时不能声称确定修复。
- 支持任务状态查询、取消和刷新恢复；工具执行记录不包含模型私有推理。
- 不修改仓库、不执行目标项目代码、不发版、不操作生产环境。
- 工具调用失败率 18%→4% 尚无固定评测依据，不能作为产品成果宣传。

## Evidence on Hand

真实分析对象为 spring-petclinic/spring-petclinic-microservices。已核验依赖变化与历史修复样本见 docs/verification。界面使用 API 实际返回的数据，不填造统计数字或假结果。

## Product Principles

1. 先完成一次真实任务，再逐步展示复杂配置。
2. 结论先于执行细节，证据始终可查。
3. 区分确定事实、相似候选与未知项。
4. 出错或拒答时说明原因和可采取的下一步。
