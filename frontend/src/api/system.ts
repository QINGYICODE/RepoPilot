export interface Capabilities {
  application: string
  phase: string
  versions: Record<string, string>
  repositoryCount: number
  liveModelEnabled: boolean
  availableCapabilities: string[]
  plannedCapabilities: string[]
}

export async function getCapabilities(): Promise<Capabilities> {
  const response = await fetch('/api/system/capabilities', {
    signal: AbortSignal.timeout(10_000),
    headers: { Accept: 'application/json' },
  })
  if (!response.ok) throw new Error(`服务返回 HTTP ${response.status}`)
  return response.json() as Promise<Capabilities>
}
