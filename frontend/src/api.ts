const TOKEN_STORAGE_KEY = 'flame_auth_token'

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_STORAGE_KEY)
}

export function setToken(token: string | null): void {
  if (token) {
    localStorage.setItem(TOKEN_STORAGE_KEY, token)
  } else {
    localStorage.removeItem(TOKEN_STORAGE_KEY)
  }
}

export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

async function readDetail(response: Response): Promise<string> {
  try {
    const payload = (await response.json()) as { detail?: unknown }
    if (typeof payload.detail === 'string') {
      return payload.detail
    }
    if (Array.isArray(payload.detail)) {
      return payload.detail.map((item) => (item as { msg?: string }).msg ?? '').join('；')
    }
  } catch {
    // ignore malformed error bodies
  }
  return `请求失败（HTTP ${response.status}）`
}

function authHeaders(): Record<string, string> {
  const token = getToken()
  return token ? { Authorization: `Bearer ${token}` } : {}
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json', ...authHeaders() }
  const response = await fetch(path, {
    headers,
    ...init,
  })
  if (!response.ok) {
    throw new ApiError(response.status, await readDetail(response))
  }
  // 204 No Content 无响应体，json() 会抛解析错误——删除接口依赖此分支
  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}

export function normalizeStatsSummary(raw: Record<string, unknown>): import('./types').StatsSummary {
  const windowDays =
    typeof raw.windowDays === 'number'
      ? raw.windowDays
      : typeof raw.window_days === 'number'
        ? raw.window_days
        : 30
  return {
    windowDays,
    daily: Array.isArray(raw.daily) ? (raw.daily as import('./types').StatsDailyRow[]) : [],
    currentSuccessStreak: typeof raw.currentSuccessStreak === 'number' ? raw.currentSuccessStreak : 0,
    bestSuccessStreak: typeof raw.bestSuccessStreak === 'number' ? raw.bestSuccessStreak : 0,
    totalJobs: typeof raw.totalJobs === 'number' ? raw.totalJobs : 0,
    succeededJobs: typeof raw.succeededJobs === 'number' ? raw.succeededJobs : 0,
    successRate: typeof raw.successRate === 'number' ? raw.successRate : null,
  }
}

export const api = {
  health: () => request<import('./types').Health>('/health'),
  authSetupStatus: () => request<import('./types').AuthSetupRead>('/api/auth/setup'),
  authInitialize: (input: { username: string; password: string; displayName?: string }) =>
    request<import('./types').AuthRead>('/api/auth/register', {
      method: 'POST',
      body: JSON.stringify(input),
    }),
  authLogin: (input: { username: string; password: string }) =>
    request<import('./types').AuthRead>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify(input),
    }),
  authMe: () => request<import('./types').User>('/api/auth/me'),
  authUpdateMe: (input: { displayName?: string | null; email?: string | null }) =>
    request<import('./types').User>('/api/auth/me', {
      method: 'PATCH',
      body: JSON.stringify(input),
    }),
  authChangePassword: (input: { currentPassword: string; newPassword: string }) =>
    request<{ message: string }>('/api/auth/me/password', {
      method: 'POST',
      body: JSON.stringify(input),
    }),
  authLogout: () => request<void>('/api/auth/logout', { method: 'POST' }),
  authSessions: () => request<import('./types').AuthSessionRead[]>('/api/auth/sessions'),
  authRevokeSession: (id: string) =>
    request<void>(`/api/auth/sessions/${id}`, { method: 'DELETE' }),
  authRecoverPassword: (input: { username: string }) =>
    request<{ message: string }>('/api/auth/password-recovery', {
      method: 'POST',
      body: JSON.stringify(input),
    }),
  authListUsers: () => request<import('./types').User[]>('/api/auth/users'),
  authAdminCreateUser: (input: {
    username: string
    password: string
    displayName?: string
    email?: string
    isSuperuser?: boolean
  }) =>
    request<import('./types').User>('/api/auth/users', {
      method: 'POST',
      body: JSON.stringify(input),
    }),
  authAdminUpdateUser: (id: string, input: { password?: string; isSuperuser?: boolean }) =>
    request<import('./types').User>(`/api/auth/users/${id}`, {
      method: 'PATCH',
      body: JSON.stringify(input),
    }),
  dashboard: () => request<import('./types').DashboardSummary>('/api/dashboard'),
  accounts: () => request<import('./types').Account[]>('/api/accounts'),
  createAccount: (input: import('./types').AccountCreateInput) =>
    request<import('./types').Account>('/api/accounts', {
      method: 'POST',
      body: JSON.stringify(input),
    }),
  patchAccount: (
    id: string,
    patch: { enabled?: boolean; displayName?: string; sendWindowStart?: string; sendWindowEnd?: string; sendTimeHhmm?: string | null },
  ) =>
    request<import('./types').Account>(`/api/accounts/${id}`, {
      method: 'PATCH',
      body: JSON.stringify(patch),
    }),
  deleteAccount: (id: string) =>
    request<void>(`/api/accounts/${id}`, { method: 'DELETE' }),
  targets: () => request<import('./types').ContractTarget[]>('/api/targets'),
  createTarget: (input: import('./types').TargetCreateInput) =>
    request<import('./types').ContractTarget>('/api/targets', {
      method: 'POST',
      body: JSON.stringify(input),
    }),
  patchTarget: (
    id: string,
    patch: { enabled?: boolean; templateId?: string | null; sendTimeHhmm?: string | null },
  ) =>
    request<import('./types').ContractTarget>(`/api/targets/${id}`, {
      method: 'PATCH',
      body: JSON.stringify(patch),
    }),
  deleteTarget: (id: string) =>
    request<void>(`/api/targets/${id}`, { method: 'DELETE' }),
  createTargetsBatch: (input: {
    accountId: string
    templateId?: string
    items: {
      nickname: string
      platformUserId?: string | null
      platformHandle?: string | null
      platformConversationId?: string | null
      conversationType?: 'user' | 'group' | null
      identityStatus?: 'bound' | 'ambiguous' | 'not_observed'
      conversationStatus?: 'bound' | 'ambiguous' | 'not_observed'
      streakDays?: number
      sparkDays?: number | null
      sparkKind?: string | null
      sparkLabel?: string | null
    }[]
  }) =>
    request<import('./types').TargetBatchResult>('/api/targets/batch', {
      method: 'POST',
      body: JSON.stringify(input),
    }),
  scanChats: (accountId: string) =>
    request<{
      overall: 'ok' | 'auth_expired' | 'error'
      detail: string | null
      entries: import('./types').ScanChatEntry[]
    }>(`/api/accounts/${accountId}/scan-chats`, { method: 'POST' }),
  startScanChats: (accountId: string) =>
    request<import('./types').ScanProgress>(`/api/accounts/${accountId}/scan-chats/start`, {
      method: 'POST',
    }),
  scanChatsProgress: (accountId: string, taskId: string) =>
    request<import('./types').ScanProgress>(
      `/api/accounts/${accountId}/scan-chats/progress?taskId=${encodeURIComponent(taskId)}`,
    ),
  cancelScanChats: (accountId: string, taskId: string) =>
    request<import('./types').ScanProgress>(
      `/api/accounts/${accountId}/scan-chats/cancel?taskId=${encodeURIComponent(taskId)}`,
      { method: 'POST' },
    ),
  latestScanChats: (accountId: string) =>
    request<import('./types').ScanProgress | null>(`/api/accounts/${accountId}/scan-chats/latest`),
  scanChatsHistory: (accountId: string, limit = 10) =>
    request<import('./types').ScanHistoryItem[]>(
      `/api/accounts/${accountId}/scan-chats/history?limit=${encodeURIComponent(limit)}`,
    ),
  startIdentityHydration: (accountId: string, targetIds: string[]) =>
    request<import('./types').IdentityHydrationProgress>(
      `/api/accounts/${accountId}/identity-hydration/start`,
      {
        method: 'POST',
        body: JSON.stringify({ targetIds }),
      },
    ),
  identityHydrationProgress: (accountId: string, taskId: string) =>
    request<import('./types').IdentityHydrationProgress>(
      `/api/accounts/${accountId}/identity-hydration/progress?taskId=${encodeURIComponent(taskId)}`,
    ),
  cancelIdentityHydration: (accountId: string, taskId: string) =>
    request<import('./types').IdentityHydrationProgress>(
      `/api/accounts/${accountId}/identity-hydration/cancel?taskId=${encodeURIComponent(taskId)}`,
      { method: 'POST' },
    ),
  latestIdentityHydration: (accountId: string) =>
    request<import('./types').IdentityHydrationProgress | null>(
      `/api/accounts/${accountId}/identity-hydration/latest`,
    ),
  sendPreview: (id: string) =>
    request<import('./types').SendPreviewRead>(`/api/accounts/${id}/send/preview`),
  sendProgress: (id: string) =>
    request<import('./types').SendProgress>(`/api/accounts/${id}/send/progress`),
  sendNow: (id: string, options: { force?: boolean } = {}) =>
    request<import('./types').SendRead>(
      `/api/accounts/${id}/send${options.force ? '?force=true' : ''}`,
      { method: 'POST' },
    ),
  sendTarget: (
    accountId: string,
    targetId: string,
    body: import('./types').SendTargetInput,
  ) =>
    request<import('./types').SendRead>(
      `/api/accounts/${accountId}/targets/${targetId}/send`,
      { method: 'POST', body: JSON.stringify(body) },
    ),
  templates: () => request<import('./types').MessageTemplate[]>('/api/templates'),
  createTemplate: (input: import('./types').TemplateCreateInput) =>
    request<import('./types').MessageTemplate>('/api/templates', {
      method: 'POST',
      body: JSON.stringify(input),
    }),
  patchTemplate: (
    id: string,
    patch: {
      name?: string
      content?: string
      imagePath?: string
      stickerName?: string
      stickerCategory?: string
      enabled?: boolean
      poolItems?: { content: string; weekdayMask: number; enabled?: boolean }[]
    },
  ) =>
    request<import('./types').MessageTemplate>(`/api/templates/${id}`, {
      method: 'PATCH',
      body: JSON.stringify(patch),
    }),
  deleteTemplate: (id: string) =>
    request<void>(`/api/templates/${id}`, { method: 'DELETE' }),
  confirmJobSent: (id: string) =>
    request<import('./types').SendJob>(`/api/jobs/${id}/confirm-sent`, { method: 'POST' }),
  releaseJob: (id: string) =>
    request<import('./types').SendJob>(`/api/jobs/${id}/release`, { method: 'POST' }),
  jobDetail: (id: string) =>
    request<import('./types').SendJobDetail>(`/api/jobs/${id}`),
  jobAttemptScreenshot: async (jobId: string, attemptNo: number): Promise<Blob> => {
    const response = await fetch(`/api/jobs/${jobId}/attempts/${attemptNo}/screenshot`, {
      headers: authHeaders(),
    })
    if (!response.ok) {
      throw new ApiError(response.status, await readDetail(response))
    }
    return response.blob()
  },
  listJobs: (params: { limit?: number; offset?: number; accountId?: string; status?: string }) => {
    const query = new URLSearchParams()
    if (params.limit != null) query.set('limit', String(params.limit))
    if (params.offset != null) query.set('offset', String(params.offset))
    if (params.accountId) query.set('accountId', params.accountId)
    if (params.status) query.set('status', params.status)
    const qs = query.toString()
    return request<import('./types').JobsPageData>(`/api/jobs${qs ? `?${qs}` : ''}`)
  },
  startLogin: (id: string) =>
    request<import('./types').LoginStatus>(`/api/accounts/${id}/login`, {
      method: 'POST',
    }),
  loginStatus: (id: string) =>
    request<import('./types').LoginStatus>(`/api/accounts/${id}/login`),
  cancelLogin: (id: string) =>
    request<import('./types').LoginStatus>(`/api/accounts/${id}/login/cancel`, {
      method: 'POST',
    }),
  dryRun: (id: string) =>
    request<import('./types').DryRunRead>(`/api/accounts/${id}/dry-run`, {
      method: 'POST',
    }),
  probeSelectors: (id: string) =>
    request<import('./types').SelectorProbeRead>(`/api/accounts/${id}/probe-selectors`, {
      method: 'POST',
    }),
  selectorsBundle: () =>
    request<import('./types').SelectorBundleRead>('/api/selectors'),
  saveSelectorOverrides: (overrides: Record<string, import('./types').SelectorValue>) =>
    request<import('./types').SelectorBundleRead>('/api/selectors', {
      method: 'PUT',
      body: JSON.stringify({ overrides }),
    }),
  clearSelectorOverrides: () =>
    request<import('./types').SelectorBundleRead>('/api/selectors/override', {
      method: 'DELETE',
    }),
  schedulerStatus: () =>
    request<import('./types').SchedulerStatus>('/api/scheduler'),
  schedulerUpdateSettings: (patch: import('./types').SchedulerSettingsPatch) =>
    request<import('./types').SchedulerStatus>('/api/scheduler/settings', {
      method: 'PATCH',
      body: JSON.stringify(patch),
    }),
  schedulerPause: () =>
    request<import('./types').SchedulerStatus>('/api/scheduler/pause', {
      method: 'POST',
    }),
  schedulerResume: () =>
    request<import('./types').SchedulerStatus>('/api/scheduler/resume', {
      method: 'POST',
    }),
  schedulerTestNotification: () =>
    request<import('./types').NotificationTestResult>('/api/scheduler/test-notification', {
      method: 'POST',
    }),
  statsSummary: () =>
    request<Record<string, unknown>>('/api/stats/summary').then(normalizeStatsSummary),
  downloadStatsCsv: async () => {
    const response = await fetch('/api/stats/export.csv', { headers: authHeaders() })
    if (!response.ok) throw new ApiError(response.status, '统计 CSV 导出失败')
    const blob = await response.blob()
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `flame-jobs-${new Date().toISOString().slice(0, 10)}.csv`
    anchor.click()
    URL.revokeObjectURL(url)
  },
  startQrLogin: (id: string) =>
    request<import('./types').LoginStatus>(`/api/accounts/${id}/login/qr`, {
      method: 'POST',
    }),
  qrLoginStatus: (id: string) =>
    request<import('./types').LoginStatus>(`/api/accounts/${id}/login/qr`),
  loginVerificationOptions: (id: string) =>
    request<import('./types').LoginVerificationOptions>(
      `/api/accounts/${id}/login/verify/options`,
    ),
  selectVerificationOption: (id: string, option: string) =>
    request<import('./types').LoginVerificationAction>(
      `/api/accounts/${id}/login/verify/select`,
      { method: 'POST', body: JSON.stringify({ option }) },
    ),
  submitVerificationCode: (id: string, code: string) =>
    request<import('./types').LoginVerificationAction>(
      `/api/accounts/${id}/login/verify/code`,
      { method: 'POST', body: JSON.stringify({ code }) },
    ),
  /** 二维码截图端点需要 Authorization 头，`<img src>` 无法携带，必须走 fetch + blob */
  qrImage: async (id: string): Promise<Blob> => {
    const response = await fetch(`/api/accounts/${id}/login/qr/image`, { headers: authHeaders() })
    if (!response.ok) throw new ApiError(response.status, '二维码截图获取失败')
    return response.blob()
  },
  downloadBackup: async () => {
    const response = await fetch('/api/backup', { headers: authHeaders() })
    if (!response.ok) throw new ApiError(response.status, '备份导出失败')
    const blob = await response.blob()
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `flame-backup-${new Date().toISOString().slice(0, 10)}.json`
    anchor.click()
    URL.revokeObjectURL(url)
  },
}
