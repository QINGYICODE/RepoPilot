import { request, type ImpactResult } from './repository'

export type Task = 'DEPENDENCY_IMPACT' | 'INCIDENT_FIX'
export interface AnalysisRequest { repoId: number; snapshotId: number; task: Task; question: string; baseSnapshotId?: number; module?: string; deployedSha?: string }
export interface Citation { id: string; snapshotId: number; type: string; sourceSha: string; path: string; startLine: number; endLine: number; excerpt: string; sourceUrl: string }
export interface AnalysisResult {
  id: string; status: string; reasonCode: string; summary: string; repository: string; knowledgeSha: string; baseSha: string | null
  citations: Citation[]; dependency: ImpactResult | null
  incident: { candidates: { commitSha: string; subject: string; classification: string; applicability: string; matchedTerms: string[]; evidence: { id: string }[] }[] } | null
  events: { sequence: number; tool: string; status: string; code: string; cached: boolean; durationMs: number }[]
  usage: { modelCalls: number; requestedToolCalls: number; executedToolCalls: number; failedToolCalls: number; rejectedToolCalls: number; cachedToolCalls: number; inputTokens: number; outputTokens: number; estimatedTokens: boolean; maxPromptBytes: number; compactedExchanges: number; durationMs: number }
  warnings: string[]
  scope?: { repoId: number; snapshotId: number; baseSnapshotId: number | null; task: Task; module: string | null; deployedSha: string | null; question?: string | null } | null
}
export function submitAnalysis(body: AnalysisRequest, signal?: AbortSignal) {
  return request<AnalysisResult>('/analysis-runs', { method: 'POST', body: JSON.stringify(body), signal })
}
export function getAnalysis(id: string, signal?: AbortSignal) { return request<AnalysisResult>(`/analysis-runs/${encodeURIComponent(id)}`, { signal }) }
export function cancelAnalysis(id: string, signal?: AbortSignal) { return request<AnalysisResult>(`/analysis-runs/${encodeURIComponent(id)}/cancel`, { method: 'POST', signal }) }
