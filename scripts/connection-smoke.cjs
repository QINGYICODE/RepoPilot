// Real public repository connection/import; no model calls. Optional Playwright install stays outside the repo.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright')
const assert = require('node:assert/strict')
const fs = require('node:fs/promises')
const path = require('node:path')
const root = path.resolve(__dirname, '..')
const base = process.env.REPOPILOT_PREVIEW_URL || 'http://127.0.0.1:5173'
async function main() {
  await fs.mkdir(path.join(root, '.local'), { recursive: true })
  const browser = await chromium.launch({ headless: true })
  try {
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
    const page = await context.newPage(), errors = []
    page.on('pageerror', error => errors.push(error.message))
    const loaded = page.waitForResponse(response => response.url().endsWith('/api/repositories') && response.ok())
    await page.goto(`${base}/repositories`)
    await page.getByRole('heading', { name: '仓库管理', exact: true }).waitFor()
    // Wait for initial repository/settings load before selecting the active form.
    await loaded
    if (await page.getByText('接入其他仓库', { exact: true }).count()) await page.getByText('接入其他仓库', { exact: true }).click()
    await page.getByLabel('GitHub 仓库地址', { exact: true }).fill('https://github.com/QINGYICODE/RepoPilot?tab=readme-ov-file')
    await page.getByRole('button', { name: '接入并授权', exact: true }).click()
    await page.waitForURL(/connection=[a-f0-9-]{36}/)
    const id = new URL(page.url()).searchParams.get('connection')
    await page.reload()
    const status = page.getByTestId('connection-status')
    await status.waitFor()
    await page.waitForFunction(() => document.querySelector('.connection-heading h3')?.textContent?.includes('仓库与代码版本已就绪'), { }, { timeout: 180000 })
    const connection = await (await page.request.get(`${base}/api/repository-connections/${id}`)).json()
    assert.equal(connection.status, 'READY')
    const job = await (await page.request.get(`${base}/api/sync-jobs/${connection.syncJobId}`)).json()
    assert.equal(job.status, 'READY')
    const files = await (await page.request.get(`${base}/api/repositories/${connection.repoId}/snapshots/${job.snapshotId}/files`)).json()
    assert(files.some(file => file.path === 'backend/pom.xml'), 'Real RepoPilot backend must be indexed')
    const search = await (await page.request.get(`${base}/api/repositories/${connection.repoId}/snapshots/${job.snapshotId}/search?q=AgentOrchestrator&limit=10`)).json()
    assert(search.items.length > 0, 'Imported repository must have searchable evidence')
    assert(search.items.every(item => item.sourceUrl.startsWith('https://github.com/qingyicode/repopilot/')), 'Evidence stays in the requested repository')
    const graph = await (await page.request.get(`${base}/api/repositories/${connection.repoId}/snapshots/${job.snapshotId}/dependency-graph`)).json()
    assert(graph.modules.some(module => module.path === 'backend' && module.service), 'Rootless Maven backend must be recognized')
    const fixes = await (await page.request.post(`${base}/api/repositories/${connection.repoId}/fix-candidates`, {
      data: { snapshotId: job.snapshotId, message: 'IMPECCABLE_HOME 告警修复' },
    })).json()
    assert(fixes.candidates.some(candidate => candidate.commitSha === 'bf9c07ff7639c2f8dc779d48eac8fbfb561613fc'), 'Real historical loader fix must have diff evidence')
    await page.screenshot({ path: path.join(root, '.local/connection-desktop.png'), fullPage: true })
    await page.setViewportSize({ width: 390, height: 844 }); await page.reload(); await status.waitFor()
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'Mobile must not overflow')
    await page.screenshot({ path: path.join(root, '.local/connection-mobile.png'), fullPage: true })

    // Controlled UI-only rate-limit response: no real GitHub quota is consumed.
    const fixture = '00000000-0000-0000-0000-000000000000'
    await page.route(`**/api/repository-connections/${fixture}`, route => route.fulfill({ json: {
      id: fixture, owner: 'fixture', name: 'rate-limited', status: 'FAILED', attempts: 1,
      repoId: null, syncJobId: null, errorCode: 'GITHUB_RATE_LIMITED', retryAt: new Date(Date.now() + 120000).toISOString(),
    } }))
    await page.goto(`${base}/repositories?connection=${fixture}`)
    await page.getByRole('button', { name: '等待 GitHub 额度恢复', exact: true }).waitFor()
    assert(await page.getByRole('button', { name: '等待 GitHub 额度恢复', exact: true }).isDisabled())
    assert((await status.innerText()).includes('GitHub 访问额度暂时用尽'))
    assert.equal(new URL(page.url()).searchParams.get('connection'), fixture)
    assert.deepEqual(errors, [])
    await fs.writeFile(path.join(root, '.local/connection-smoke.json'), JSON.stringify({
      repository: 'QINGYICODE/RepoPilot', connectionId: id, repoId: connection.repoId,
      snapshotId: job.snapshotId, sha: job.targetSha, fileCount: files.length,
      searchEvidenceCount: search.items.length, restoredAfterRefresh: true, mobileWidth: 390,
      moduleCount: graph.modules.length, services: graph.modules.filter(module => module.service).map(module => module.path),
      historicalFixes: fixes.candidates.map(candidate => candidate.commitSha),
      rateLimitUi: 'CONTROLLED_HTTP_FIXTURE', browserErrors: errors,
    }, null, 2) + '\n')
    console.log('Verified real RepoPilot connection, import, search, refresh recovery and mobile; rate-limit UI uses a controlled response.')
  } finally { await browser.close() }
}
main().catch(error => { console.error(error); process.exitCode = 1 })
