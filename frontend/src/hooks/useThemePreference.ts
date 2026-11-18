import { useCallback, useEffect, useState } from 'react'
import {
  applyResolvedThemeToDocument,
  normalizeThemePreference,
  resolveEffectiveTheme,
  THEME_STORAGE_KEY,
  type ResolvedTheme,
  type ThemePreference,
} from '../lib/theme.ts'

function readSystemDark(): boolean {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
    return false
  }
  return window.matchMedia('(prefers-color-scheme: dark)').matches
}

export function useThemePreference() {
  const [preference, setPreferenceState] = useState<ThemePreference>(() => {
    try {
      return normalizeThemePreference(localStorage.getItem(THEME_STORAGE_KEY))
    } catch {
      return 'system'
    }
  })

  const [systemDark, setSystemDark] = useState<boolean>(readSystemDark)

  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
      return undefined
    }
    const mql = window.matchMedia('(prefers-color-scheme: dark)')
    const onChange = (event: MediaQueryListEvent) => {
      setSystemDark(event.matches)
    }
    setSystemDark(mql.matches)
    mql.addEventListener('change', onChange)
    return () => mql.removeEventListener('change', onChange)
  }, [])

  const resolvedTheme: ResolvedTheme = resolveEffectiveTheme(preference, systemDark)

  useEffect(() => {
    applyResolvedThemeToDocument(resolvedTheme)
  }, [resolvedTheme])

  const setPreference = useCallback((next: ThemePreference) => {
    setPreferenceState(next)
    try {
      localStorage.setItem(THEME_STORAGE_KEY, next)
    } catch {
      // ignore storage failure
    }
  }, [])

  return {
    preference,
    resolvedTheme,
    setPreference,
  }
}
