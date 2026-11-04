import type { Snapshot } from '../api/repository.js'

function compareIndex(left: Snapshot, right: Snapshot) {
  const a = (left.indexVersion ?? '').match(/\d+/g)?.map(Number) ?? []
  const b = (right.indexVersion ?? '').match(/\d+/g)?.map(Number) ?? []
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    const difference = (a[i] ?? 0) - (b[i] ?? 0)
    if (difference) return difference
  }
  return left.id - right.id
}

/** One choice per commit; an explicit historic run/link may pin its original immutable snapshot. */
export function preferredSnapshots(list: Snapshot[], activeId?: number | null, pinnedIds: number[] = []): Snapshot[] {
  const pinned = new Set(pinnedIds), selected = new Map<string, Snapshot>()
  for (const item of list) {
    const previous = selected.get(item.commitSha)
    if (!previous || (pinned.has(item.id) && !pinned.has(previous.id)) ||
        (pinned.has(item.id) === pinned.has(previous.id) && compareIndex(item, previous) > 0)) selected.set(item.commitSha, item)
  }
  const activeSha = list.find(item => item.id === activeId)?.commitSha
  return [...selected.values()].sort((a, b) => Number(b.commitSha === activeSha) - Number(a.commitSha === activeSha) || b.id - a.id)
}
