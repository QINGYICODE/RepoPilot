# 固定回归集

`cases.json` 包含 50 条固定提示，分为 25 组事实或边界：依赖影响 12、历史修复 12、无证据 8、范围外 10、越权 8。每个组内的改写和部署状态变体固定留在同一 split，避免相同 commit 的改写同时进入调参与保留集。

标签由 Agent 根据仓库中既有的真实 POM diff、历史 patch 和确定性权限规则整理，**不是 50 条人工标注的生产事故**。修复查询为 `SIMULATED_ALERT_FROM_REAL_PATCH`，负例与边界标明合成来源；没有 Issue/事故关联时只能预期 `RELATED_CANDIDATE`。基础事实引用 `backend/src/test/resources/real-dependency-cases.json`、`real-fix-cases.json` 及 `docs/verification/repository-connection-real.json`。

先启动本机后端，并在仓库管理中显式授权 RepoPilot；PetClinic 使用预设范围。默认不自动授权任何仓库。

```bash
# 导入缺少的固定版本，执行 50 条确定性工具/边界回归；不调用模型
python3 scripts/evaluate.py --prepare

# 索引已备齐时重复执行，避免将首次导入时间混入查询延迟
python3 scripts/evaluate.py --output .local/evaluation/warm.json

# 真实 Agent 评测，调用已经配置的模型
python3 scripts/evaluate.py --mode agent --split holdout --output .local/evaluation/agent-holdout.json

# 也可明确选择小规模真实模型用例
python3 scripts/evaluate.py --mode agent --case RP-002 --case RP-024 --output .local/evaluation/agent-subset.json
```

`--prepare` 通过正常同步接口导入已授权仓库，可能改变当前活跃版本；需要时在仓库管理重新选择目标提交。没有 `--prepare` 时，缺少索引记为 `SETUP_MISSING`，不假装完成；全部选中用例通过才返回退出码 0。`--case`、`--split`、`--limit` 会明确记录实际样本数。

JSON 报告记录清单哈希、代码 commit、工作区是否有改动、索引/提示/模型版本、每条结果与耗时、输入输出 token 和是否估算；同目录 Markdown 便于检查失败用例。代码仅使用 Python 标准库，不读取或输出模型密钥。

指标分开统计：用例通过率、业务用例通过率、拒答通过率、去重后的原文引用有效率、Agent 工具执行失败和参数/权限拦截。HTTP 失败不会当成无证据拒答；未执行模型工具时，失败率分母为 0，结果为 null。首次索引导入与预热查询报告须分别解读，不把缓存查询耗时当成冷启动耗时。

本集用于版本回归；人工复核、完整的真实模型 50 条测量、历史基线和前后同条件对照仍需另外完成。不能用 tools 模式、改写样本数量或一轮通过率证明工具失败率从 18% 降到 4%。
