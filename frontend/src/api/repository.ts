export interface Repository { id: number; owner: string; name: string; defaultRef: string; activeSnapshotId: number | null }
export interface Snapshot { id: number; commitSha: string; coverage: { level: string; historyComplete: boolean; warnings: string[] }; stats: Record<string, number | string> }
export interface SyncJob { id: string; repoId: number; targetSha: string; snapshotId: number | null; status: string; errorCode: string | null }
export interface Evidence { id: string; type: string; sourceSha: string; parentSha: string | null; path: string; symbol: string; startLine: number; endLine: number; excerpt: string; sourceUrl: string; contentHash: string; truncated: boolean; locator: Record<string, unknown> }
export interface SearchPage { items: Evidence[]; nextCursor: string | null; truncated: boolean; retrievalMethod: string }
export interface Dependency { coordinate: string; version: string; scope: string; resolution: string }
export interface ImpactResult {
  baseSnapshotId: number; headSnapshotId: number
  changes: { id: string; module: string; coordinate: string; kind: string; before: Dependency | null; after: Dependency | null }[]
  impacts: { changeId: string; service: string; view: 'BASE' | 'HEAD'; kind: string; scope: string; path: string[]; evidenceIds: string[] }[]
  affectedServices: string[]; warnings: string[]; truncated: boolean; coverage: string
}

export async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const response = await fetch(`/api${path}`, { ...options, signal: options.signal ?? AbortSignal.timeout(20_000), headers: { Accept: 'application/json', ...(options.body ? { 'Content-Type': 'application/json' } : {}), ...options.headers } })
  if (!response.ok) {
    const body = await response.json().catch(() => ({})) as { code?: string }
    throw new Error(body.code ?? `HTTP ${response.status}`)
  }
  return response.json() as Promise<T>
}

export function post<T>(path: string, body: unknown) { return request<T>(path, { method: 'POST', body: JSON.stringify(body) }) }
