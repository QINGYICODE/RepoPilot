import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { Task } from '../api/analysis'

interface Entry { id: string; task: Task }
export const useHistoryStore = defineStore('history', () => {
  const entries = ref<Entry[]>([])
  try {
    const saved: unknown = JSON.parse(localStorage.getItem('repopilot.recent-runs') ?? '[]')
    if (Array.isArray(saved)) entries.value = saved.filter((x): x is Entry => typeof x?.id === 'string' && /^[a-f0-9-]{36}$/.test(x.id) && ['DEPENDENCY_IMPACT', 'INCIDENT_FIX'].includes(x.task)).slice(0, 6)
  } catch { /* Recent task IDs are optional; storage failures do not block analysis. */ }
  function add(id: string, task: Task) {
    entries.value = [{ id, task }, ...entries.value.filter(e => e.id !== id)].slice(0, 6)
    try { localStorage.setItem('repopilot.recent-runs', JSON.stringify(entries.value)) } catch { /* Browsing can continue without persistence. */ }
  }
  return { entries, add }
})
