<script setup lang="ts">
import { RouterLink } from 'vue-router'
import AppIcon from '../components/AppIcon.vue'
</script>
<template>
  <header class="page-heading"><div><h1>使用指南</h1><p>从一个真实仓库问题开始，逐步核对分析结论。</p></div><RouterLink class="button secondary" to="/">返回分析<AppIcon name="arrow" :size="15" /></RouterLink></header>
  <div class="guide-content">
    <section><h2>第一次使用，直接试一个真实问题</h2><p>已导入 Spring PetClinic 示例版本时，无需手动寻找 SHA。选择下面的场景，检查填好的范围，再点击“开始分析”。</p><div class="inline-form"><RouterLink class="button primary" to="/?example=impact">试试依赖影响分析<AppIcon name="branch" :size="16" /></RouterLink><RouterLink class="button secondary" to="/?example=fix">试试告警历史修复<AppIcon name="search" :size="16" /></RouterLink></div></section>
    <section><h2>分析自己的问题</h2><ol><li><strong>接入仓库并导入版本。</strong> 在仓库管理中填写允许访问的 GitHub 仓库，导入目标提交。依赖比较需要两个版本。</li><li><strong>选择任务并输入问题。</strong> 依赖问题关注升级和使用方；告警问题尽量保留异常、配置键或堆栈关键字。点击“调整范围”可更换版本和模块。</li><li><strong>先看结论，再核对证据。</strong> 依赖结果列出服务和版本变化；告警结果列出历史修复候选。点击“查看原文证据”核对文件、行号和 GitHub 原文。</li></ol><RouterLink class="text-link" to="/repositories">前往仓库管理<AppIcon name="chevron" :size="14" /></RouterLink></section>
    <section><h2>几个容易混淆的概念</h2><dl class="guide-definitions"><dt>变更前 / 变更后版本</dt><dd>用于比较依赖的两个完整提交。界面同时显示短 SHA 和真实提交说明，避免混用不同版本。</dd><dt>查阅的代码版本</dt><dd>告警检索的知识上界，只在这个版本可达的仓库历史中找证据。</dd><dt>部署版本</dt><dd>告警发生时实际运行的完整提交 SHA。可选，但缺少它时，无法判断修复提交是否已包含在部署历史中。</dd><dt>相似修复候选</dt><dd>存在相关特征和真实修改内容的提交。它是排查线索，不能直接认定为本次故障的确定修复。</dd></dl></section>
    <section><h2>遇到“无法给出结论”怎么办？</h2><ul class="plain-list"><li><strong>没有足够证据：</strong>补充更具体的异常类型、配置键或模块，或更换查阅版本。</li><li><strong>超出分析范围：</strong>这里只回答所选仓库的依赖和历史修复问题。</li><li><strong>超出授权范围：</strong>不能访问其他未授权仓库、读取密钥、执行命令或修改代码。</li><li><strong>模型未启用：</strong>到服务状态页检查，配置模型后重新启动 Agent 后端。</li></ul><p>索引覆盖有限、外部依赖未解析、运行时调用未分析等情况，会在结果的“结论的适用范围”中说明。</p></section>
    <section><h2>分析过程会保留什么？</h2><p>工具名称、状态、引用、用量和终止原因会保留在分析记录中。刷新页面可恢复结果，最近分析只在本机保存任务 ID 和任务类型，不保存你输入的日志。</p><p>所有分析工具均为只读，不执行目标仓库代码，也不发版或操作生产环境。</p></section>
  </div>
</template>
