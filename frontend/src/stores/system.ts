import { ref } from 'vue'
import { defineStore } from 'pinia'
import { getCapabilities, type Capabilities } from '../api/system'

export const useSystemStore = defineStore('system', () => {
  const capabilities = ref<Capabilities | null>(null)
  const loading = ref(false)
  const error = ref('')
  const checkedAt = ref('')

  async function refresh() {
    if (loading.value) return
    loading.value = true
    error.value = ''
    try {
      capabilities.value = await getCapabilities()
      checkedAt.value = new Date().toLocaleTimeString('zh-CN')
    } catch (cause) {
      capabilities.value = null
      error.value = cause instanceof Error ? cause.message : '暂时无法连接服务'
    } finally {
      loading.value = false
    }
  }

  return { capabilities, loading, error, checkedAt, refresh }
})
