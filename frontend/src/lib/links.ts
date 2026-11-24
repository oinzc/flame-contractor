export const PROJECT_LINKS = {
  repoDisplayPath: 'github.com/oinzc/flame-contractor',
  repoUrl: 'https://github.com/oinzc/flame-contractor',
  releasesUrl: 'https://github.com/oinzc/flame-contractor/releases',
  issuesUrl: 'https://github.com/oinzc/flame-contractor/issues',
  discussionsUrl: 'https://github.com/oinzc/flame-contractor/discussions',
} as const

export interface BugReportLinkOptions {
  version?: string | null
  isDesktop?: boolean
}

export function isAllowedProjectExternalUrl(url: string): boolean {
  const clean = url.trim()
  if (!clean.startsWith(PROJECT_LINKS.repoUrl)) return false
  if (clean.length > PROJECT_LINKS.repoUrl.length) {
    const nextChar = clean[PROJECT_LINKS.repoUrl.length]
    if (nextChar !== '/' && nextChar !== '?' && nextChar !== '#') {
      return false
    }
  }
  return !/[\s"'`|\r\n\0]/.test(clean)
}

export function formatProjectVersion(raw?: string | null): string {
  const clean = (raw ?? '').trim()
  if (!clean) return 'v0.5.0'
  return clean.startsWith('v') || clean.startsWith('V') ? clean : `v${clean}`
}

export function buildBugReportUrl(options: BugReportLinkOptions = {}): string {
  const params = new URLSearchParams()
  params.set('template', 'bug_report.yml')
  params.set('version', formatProjectVersion(options.version))
  params.set(
    'environment',
    options.isDesktop ? 'Windows 桌面端（安装包）' : 'Docker Compose（推荐）',
  )
  return `${PROJECT_LINKS.repoUrl}/issues/new?${params.toString()}`
}

export function buildFeatureRequestUrl(): string {
  return `${PROJECT_LINKS.repoUrl}/issues/new?template=feature_request.yml`
}
