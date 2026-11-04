import assert from 'node:assert/strict'
import test from 'node:test'
import { preferredSnapshots } from './snapshots.js'
import type { Snapshot } from '../api/repository.js'
const snapshot = (id: number, commitSha: string, indexVersion: string): Snapshot => ({ id, commitSha, indexVersion, coverage: { level: 'PARTIAL', historyComplete: true, warnings: [] }, stats: {} })
test('reindexing keeps one commit choice and prefers the newest index over a later legacy import', () => {
  const source = [snapshot(5, 'a', 'v2.2'), snapshot(4, 'a', 'v2.10'), snapshot(3, 'a', 'v2.3'), snapshot(2, 'b', 'v2.3')]
  assert.deepEqual(preferredSnapshots(source).map(s => s.id), [4, 2])
  assert.equal(source.length, 4)
})
test('active commit stays first using its preferred index', () => {
  assert.deepEqual(preferredSnapshots([snapshot(5, 'b', 'v2.3'), snapshot(4, 'a', 'v2.3'), snapshot(1, 'a', 'v2.2')], 1).map(s => s.id), [4, 5])
})
test('an explicit immutable historical link retains its original index', () => {
  assert.deepEqual(preferredSnapshots([snapshot(4, 'a', 'v2.3'), snapshot(1, 'a', 'v2.2')], null, [1]).map(s => s.id), [1])
})
test('empty lists and missing active versions are safe', () => {
  assert.deepEqual(preferredSnapshots([], 99), [])
  assert.deepEqual(preferredSnapshots([snapshot(1, 'a', 'v2.3')], 99).map(s => s.id), [1])
})
