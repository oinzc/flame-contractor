import { useCallback, useEffect, useRef, useState } from 'react'
import { resolveDrawerOnViewChange } from '../lib/workspace.ts'
import type { Account, DrawerState, WorkspaceView } from '../types.ts'

const ACTIVE_ACCOUNT_KEY = 'flame_active_account_id'
const VALID_VIEWS: ReadonlySet<string> = new Set(['targets', 'jobs', 'templates', 'settings'])

function readInitialUrl(): { accountId: string; view: WorkspaceView } {
  const params = new URLSearchParams(window.location.search)
  const rawView = params.get('view') ?? 'targets'
  const view = (VALID_VIEWS.has(rawView) ? rawView : 'targets') as WorkspaceView
  const accountId =
    params.get('account') || localStorage.getItem(ACTIVE_ACCOUNT_KEY) || ''
  return { accountId, view }
}

export function useWorkspaceUrlState(accounts: Account[], syncUrlEnabled = true) {
  const [selectedAccountId, setSelectedAccountIdState] = useState<string>(
    () => readInitialUrl().accountId,
  )
  const [view, setViewState] = useState<WorkspaceView>(() => readInitialUrl().view)
  const [drawer, setDrawer] = useState<DrawerState>({ kind: 'closed' })
  const selectedRef = useRef(selectedAccountId)
  selectedRef.current = selectedAccountId

  // Ensure selectedAccountId always points to an existing account once loaded
  useEffect(() => {
    if (accounts.length === 0) {
      if (selectedAccountId !== '') setSelectedAccountIdState('')
      return
    }
    const exists = accounts.some((acct) => acct.id === selectedAccountId)
    if (!exists) {
      const saved = localStorage.getItem(ACTIVE_ACCOUNT_KEY)
      const fallback =
        (saved && accounts.find((acct) => acct.id === saved)?.id) || accounts[0].id
      setSelectedAccountIdState(fallback)
    }
  }, [accounts, selectedAccountId])

  // Sync URL query & localStorage without reloading when authenticated
  useEffect(() => {
    if (!syncUrlEnabled) return
    if (selectedAccountId) {
      localStorage.setItem(ACTIVE_ACCOUNT_KEY, selectedAccountId)
    }
    const url = new URL(window.location.href)
    if (selectedAccountId) {
      url.searchParams.set('account', selectedAccountId)
    } else {
      url.searchParams.delete('account')
    }
    url.searchParams.set('view', view)
    window.history.replaceState(null, '', `${url.pathname}?${url.searchParams.toString()}`)
  }, [selectedAccountId, syncUrlEnabled, view])

  const setSelectedAccountId = useCallback((nextId: string) => {
    if (selectedRef.current !== nextId) {
      setDrawer((current) =>
        current.kind === 'template-editor' || current.kind === 'create-account'
          ? current
          : { kind: 'closed' },
      )
    }
    setSelectedAccountIdState(nextId)
  }, [])

  const viewRef = useRef(view)
  viewRef.current = view

  const setView = useCallback((nextView: WorkspaceView) => {
    const prevView = viewRef.current
    setViewState(nextView)
    setDrawer((current) => resolveDrawerOnViewChange(prevView, nextView, current))
  }, [])

  const closeDrawer = useCallback(() => {
    setDrawer({ kind: 'closed' })
  }, [])

  return {
    selectedAccountId,
    setSelectedAccountId,
    view,
    setView,
    drawer,
    setDrawer,
    closeDrawer,
  }
}
