<template>
  <header class="page-header"><span class="eyebrow">WORKSPACE / SCOPE</span><span class="phase-tag">分析原则</span></header>
  <section class="scope-heading"><p class="eyebrow">明确边界，才能信任结果</p><h1>以代码库为范围，<br />以证据为依据。</h1><p class="hero-description">RepoPilot 面向单个已授权仓库的运维分析，每次任务绑定明确的代码版本。</p></section>
  <div class="feature-grid">
    <article class="feature-card"><span class="feature-number">01 / REPOSITORY</span><h3>固定仓库与版本</h3><p>依赖变化使用 base / head；告警分析保留部署版本和知识上界，避免混用不同版本的证据。</p></article>
    <article class="feature-card"><span class="feature-number">02 / EVIDENCE</span><h3>每条主张可复核</h3><p>结果关联文件、行号、commit 或 diff。相似标题只用于召回，不能独立证明某个修复适用于告警。</p></article>
    <article class="feature-card"><span class="feature-number">03 / BOUNDARY</span><h3>缺少证据时明确说明</h3><p>越界问题拒答；只有部分证据时给出局部结论。索引不全、网络失败和版本未知分别标注。</p></article>
    <article class="feature-card"><span class="feature-number">04 / READ ONLY</span><h3>工具保持只读</h3><p>首期检索代码与提交记录，不修改代码、执行 Shell 或发版。当前 D1 仅开放本地能力探针，仓库分析尚未实现。</p></article>
  </div>
</template>
