// Browser regression for version deduplication and immutable task restoration; no model calls.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright')
const assert = require('node:assert/strict')
const fs = require('node:fs/promises')
const path = require('node:path')
const base = process.env.REPOPILOT_PREVIEW_URL || 'http://127.0.0.1:5173'
async function main() {
  const browser = await chromium.launch({ headless: true })
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } }), errors = []
    page.on('pageerror', error => errors.push(error.message))
    const repos = await (await page.request.get(`${base}/api/repositories`)).json()
    const repo = repos.find(r => `${r.owner}/${r.name}`.toLowerCase() === 'qingyicode/repopilot')
    assert(repo, 'Authorize RepoPilot before this browser check')
    const snapshots = await (await page.request.get(`${base}/api/repositories/${repo.id}/snapshots`)).json()
    const head = snapshots.find(s => s.indexVersion === 'v2.3' && s.stats.moduleCount > 0)
    assert(head)
    const created = await (await page.request.post(`${base}/api/analysis-runs`, { data: {
      repoId: repo.id, snapshotId: head.id, task: 'INCIDENT_FIX', module: 'backend',
      question: '今天天气怎么样？', deployedSha: head.commitSha,
    } })).json()
    assert.equal(created.reasonCode, 'OUT_OF_SCOPE'); assert.equal(created.usage.modelCalls, 0)
    assert.equal(created.scope.snapshotId, head.id)
    // Start with no recent-run storage and no repo query: restoration must use server-bound scope.
    await page.goto(`${base}/?run=${created.id}`)
    await page.waitForFunction(id => document.querySelector('select[aria-label="GitHub 仓库"]')?.value === String(id), repo.id)
    await page.waitForFunction(() => document.querySelector('input[aria-label="查找历史修复"]')?.checked)
    await page.getByRole('button', { name: '调整范围', exact: true }).click()
    await page.waitForFunction(() => document.querySelector('select[aria-label="告警所属模块"]')?.value === 'backend')
    assert.equal(await page.getByLabel('查阅的代码版本', { exact: true }).inputValue(), String(head.id))
    assert.equal(await page.getByLabel('告警所属模块', { exact: true }).inputValue(), 'backend')
    assert.equal(await page.getByLabel('部署版本 SHA', { exact: true }).inputValue(), head.commitSha)
    assert.equal(await page.locator('#analysis-question').inputValue(), '今天天气怎么样？')
    await page.reload()
    await page.waitForFunction(() => document.querySelector('input[aria-label="查找历史修复"]')?.checked)
    await page.goto(`${base}/repositories`)
    await page.getByLabel('选择仓库', { exact: true }).selectOption(String(repo.id))
    await page.waitForFunction(count => document.querySelectorAll('.version-list article').length === count, new Set(snapshots.map(s => s.commitSha)).size)
    await page.screenshot({ path: path.resolve(__dirname, '../.local/workspace-versions.png'), fullPage: true })
    const legacy = snapshots.find(s => s.indexVersion !== 'v2.3' && snapshots.some(other => other.commitSha === s.commitSha && other.indexVersion === 'v2.3'))
    if (legacy) {
      await page.goto(`${base}/?repo=${repo.id}&snapshot=${legacy.id}`)
      await page.getByRole('button', { name: '调整范围', exact: true }).click()
      await page.waitForFunction(id => document.querySelector('select[aria-label="变更后版本"]')?.value === String(id), legacy.id)
    }
    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto(`${base}/?run=${created.id}`)
    await page.waitForFunction(() => document.querySelector('input[aria-label="查找历史修复"]')?.checked)
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
    await page.screenshot({ path: path.resolve(__dirname, '../.local/workspace-restored-mobile.png'), fullPage: true })
    assert.deepEqual(errors, [])
    await fs.writeFile(path.resolve(__dirname, '../.local/workspace-smoke.json'), JSON.stringify({
      versionChoices: new Set(snapshots.map(s => s.commitSha)).size, rawSnapshots: snapshots.length,
      restoredTask: created.scope.task, restoredRepo: repo.id, pinnedLegacyLink: !!legacy, modelCalls: 0, browserErrors: errors,
    }, null, 2))
    console.log('Verified distinct commit choices, cross-repository task restore, module/deployed SHA, legacy link and mobile; no model calls.')
  } finally { await browser.close() }
}
main().catch(error => { console.error(error); process.exitCode = 1 })
