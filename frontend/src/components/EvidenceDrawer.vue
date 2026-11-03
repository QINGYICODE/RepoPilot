<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import type { Citation } from '../api/analysis'
import AppIcon from './AppIcon.vue'
import { evidenceType, shortSha } from '../lib/presentation'
const props = defineProps<{ citations: Citation[]; selectedId: string | null; repository: string }>()
const emit = defineEmits<{ close: []; select: [id: string] }>()
const dialog = ref<HTMLDialogElement>(), copied = ref(false)
const selected = computed(() => props.citations.find(c => c.id === props.selectedId))
const source = computed(() => selected.value?.sourceUrl.startsWith(`https://github.com/${props.repository}/`) ? selected.value.sourceUrl : undefined)
watch(() => props.selectedId, async value => {
  copied.value = false; await nextTick()
  if (value && !dialog.value?.open) dialog.value?.showModal()
  else if (!value && dialog.value?.open) dialog.value.close()
})
async function copy() {
  if (!selected.value) return
  try { await navigator.clipboard.writeText(selected.value.sourceSha); copied.value = true } catch { copied.value = false }
}
</script>
<template>
  <dialog ref="dialog" class="evidence-drawer" aria-labelledby="evidence-title" @cancel.prevent="emit('close')" @close="emit('close')" @click="event => { if (event.target === dialog) emit('close') }">
    <div class="drawer-content">
      <header class="drawer-header"><div><p class="muted">核对分析依据</p><h2 id="evidence-title">原文证据</h2></div><button class="icon-button" aria-label="关闭原文证据" autofocus @click="emit('close')"><AppIcon name="close" /></button></header>
      <label class="field"><span>选择证据</span><select :value="selectedId" aria-label="选择证据" @change="emit('select', ($event.target as HTMLSelectElement).value)"><option v-for="(citation, i) in citations" :key="citation.id" :value="citation.id">{{ i + 1 }}. {{ evidenceType(citation.type) }} · {{ citation.path || shortSha(citation.sourceSha) }}</option></select></label>
      <template v-if="selected"><div class="source-location"><AppIcon :name="selected.type === 'COMMIT' ? 'branch' : 'file'" /><strong>{{ selected.path || '提交说明' }}</strong></div><div class="source-meta"><code>{{ shortSha(selected.sourceSha) }}</code><span v-if="selected.startLine > 0">第 {{ selected.startLine }}–{{ selected.endLine }} 行</span><button class="text-button" @click="copy"><AppIcon :name="copied ? 'check' : 'copy'" :size="14" />{{ copied ? '已复制 SHA' : '复制 SHA' }}</button></div><pre class="source-code">{{ selected.excerpt }}</pre><p class="field-help">这里展示的是当前任务引用的代码片段。完整上下文请在 GitHub 核对。</p><a v-if="source" :href="source" target="_blank" rel="noopener noreferrer" class="button secondary">在 GitHub 查看原文<AppIcon name="external" :size="15" /></a></template>
    </div>
  </dialog>
</template>
