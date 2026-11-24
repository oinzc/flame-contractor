import test from 'node:test'
import assert from 'node:assert/strict'
import {
  PROJECT_LINKS,
  buildBugReportUrl,
  buildFeatureRequestUrl,
  formatProjectVersion,
  isAllowedProjectExternalUrl,
} from '../src/lib/links.ts'

test('PROJECT_LINKS targets canonical oinzc/flame-contractor repository', () => {
  assert.equal(PROJECT_LINKS.repoUrl, 'https://github.com/oinzc/flame-contractor')
  assert.equal(PROJECT_LINKS.releasesUrl, 'https://github.com/oinzc/flame-contractor/releases')
  assert.equal(PROJECT_LINKS.issuesUrl, 'https://github.com/oinzc/flame-contractor/issues')
  assert.equal(PROJECT_LINKS.discussionsUrl, 'https://github.com/oinzc/flame-contractor/discussions')
  assert.equal(
    buildFeatureRequestUrl(),
    'https://github.com/oinzc/flame-contractor/issues/new?template=feature_request.yml',
  )
})

test('buildBugReportUrl pre-fills desktop vs docker environment and normalized version', () => {
  const desktopUrl = buildBugReportUrl({ version: '0.5.0', isDesktop: true })
  const desktopParams = new URL(desktopUrl).searchParams
  assert.equal(desktopParams.get('template'), 'bug_report.yml')
  assert.equal(desktopParams.get('version'), 'v0.5.0')
  assert.equal(desktopParams.get('environment'), 'Windows 桌面端（安装包）')

  const webUrl = buildBugReportUrl({ version: 'v0.5.0', isDesktop: false })
  const webParams = new URL(webUrl).searchParams
  assert.equal(webParams.get('template'), 'bug_report.yml')
  assert.equal(webParams.get('version'), 'v0.5.0')
  assert.equal(webParams.get('environment'), 'Docker Compose（推荐）')
})

test('isAllowedProjectExternalUrl strictly allows canonical repository URLs only', () => {
  assert.equal(isAllowedProjectExternalUrl(PROJECT_LINKS.repoUrl), true)
  assert.equal(isAllowedProjectExternalUrl(PROJECT_LINKS.issuesUrl), true)
  assert.equal(isAllowedProjectExternalUrl(buildBugReportUrl({ version: '0.5.0' })), true)
  assert.equal(isAllowedProjectExternalUrl('https://github.com/oinzc/flame-contractor-evil'), false)
  assert.equal(isAllowedProjectExternalUrl('https://evil.example.com/oinzc/flame-contractor'), false)
  assert.equal(isAllowedProjectExternalUrl('https://github.com/oinzc/flame-contractor "bad"'), false)
  assert.equal(formatProjectVersion(''), 'v0.5.0')
})
