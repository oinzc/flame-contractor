import { useCallback, useEffect, useRef, useState } from 'react'
import { api, ApiError } from '../api.ts'
import type { LoginStatus } from '../types.ts'

export function useQrLogin(
  accountId: string | null,
  active: boolean,
  onLoginSucceeded: () => Promise<void> | void,
) {
  const [qrStatus, setQrStatus] = useState<LoginStatus | null>(null)
  const [qrBlobUrl, setQrBlobUrl] = useState<string | null>(null)
  const [verifyOptions, setVerifyOptions] = useState<string[]>([])
  const [verifyAutoOption, setVerifyAutoOption] = useState<string | null>(null)
  const [verifyBusy, setVerifyBusy] = useState(false)
  const [verifyFeedback, setVerifyFeedback] = useState<string | null>(null)

  const [windowStatus, setWindowStatus] = useState<LoginStatus | null>(null)
  const [error, setError] = useState<string | null>(null)

  const qrTimerRef = useRef<number | null>(null)
  const winTimerRef = useRef<number | null>(null)
  const blobUrlRef = useRef<string | null>(null)
  const sessionGenRef = useRef(0)

  const clearTimers = useCallback(() => {
    sessionGenRef.current += 1
    if (qrTimerRef.current !== null) {
      window.clearTimeout(qrTimerRef.current)
      qrTimerRef.current = null
    }
    if (winTimerRef.current !== null) {
      window.clearTimeout(winTimerRef.current)
      winTimerRef.current = null
    }
  }, [])

  const replaceBlobUrl = useCallback((nextUrl: string | null) => {
    const prev = blobUrlRef.current
    blobUrlRef.current = nextUrl
    setQrBlobUrl(nextUrl)
    if (prev && prev !== nextUrl) {
      URL.revokeObjectURL(prev)
    }
  }, [])

  // Cleanup & bump session generation on account switch or drawer close/unmount
  useEffect(() => {
    clearTimers()
    setQrStatus(null)
    setWindowStatus(null)
    setVerifyOptions([])
    setVerifyAutoOption(null)
    setVerifyFeedback(null)
    setError(null)
    replaceBlobUrl(null)
    return () => {
      clearTimers()
      if (blobUrlRef.current) {
        URL.revokeObjectURL(blobUrlRef.current)
        blobUrlRef.current = null
      }
    }
  }, [accountId, active, clearTimers, replaceBlobUrl])

  const pollQr = useCallback(
    async (id: string, gen: number) => {
      try {
        const status = await api.qrLoginStatus(id)
        if (gen !== sessionGenRef.current) return
        setQrStatus(status)

        if (status.qrImage) {
          try {
            const blob = await api.qrImage(id)
            if (gen !== sessionGenRef.current) return
            replaceBlobUrl(URL.createObjectURL(blob))
          } catch {
            // frame may still be writing; keep previous frame
          }
        }

        if (
          status.state === 'succeeded' ||
          status.state === 'failed' ||
          status.state === 'expired'
        ) {
          if (status.state === 'succeeded') {
            replaceBlobUrl(null)
            await onLoginSucceeded()
          }
          return
        }

        if (status.state === 'verification_required') {
          try {
            const opts = await api.loginVerificationOptions(id)
            if (gen !== sessionGenRef.current) return
            if (opts.state === 'verification_required') {
              setVerifyOptions(opts.options)
              setVerifyAutoOption(opts.autoOption ?? null)
            }
          } catch {
            // verification window may have closed
          }
        }

        if (gen !== sessionGenRef.current) return
        qrTimerRef.current = window.setTimeout(() => {
          void pollQr(id, gen)
        }, 2500)
      } catch (err) {
        if (gen !== sessionGenRef.current) return
        setError(err instanceof ApiError ? err.message : '读取扫码状态失败')
      }
    },
    [onLoginSucceeded, replaceBlobUrl],
  )

  const startQr = useCallback(async () => {
    if (!accountId) return
    clearTimers()
    const gen = sessionGenRef.current
    setError(null)
    setVerifyFeedback(null)
    try {
      const status = await api.startQrLogin(accountId)
      if (gen !== sessionGenRef.current) return
      setQrStatus(status)
      void pollQr(accountId, gen)
    } catch (err) {
      if (gen !== sessionGenRef.current) return
      setError(err instanceof ApiError ? err.message : '启动截图扫码失败')
    }
  }, [accountId, clearTimers, pollQr])

  const selectVerifyOption = useCallback(
    async (option: string) => {
      if (!accountId) return
      setVerifyBusy(true)
      setVerifyFeedback(null)
      try {
        const res = await api.selectVerificationOption(accountId, option)
        setVerifyFeedback(res.detail ?? '已选择验证方式')
      } catch (err) {
        setVerifyFeedback(err instanceof ApiError ? err.message : '切换验证方式失败')
      } finally {
        setVerifyBusy(false)
      }
    },
    [accountId],
  )

  const submitVerifyCode = useCallback(
    async (code: string) => {
      if (!accountId || !code.trim()) return false
      setVerifyBusy(true)
      setVerifyFeedback(null)
      try {
        const res = await api.submitVerificationCode(accountId, code.trim())
        setVerifyFeedback(res.detail ?? '验证码已提交，正在等待平台确认…')
        return res.ok
      } catch (err) {
        setVerifyFeedback(err instanceof ApiError ? err.message : '提交验证码失败')
        return false
      } finally {
        setVerifyBusy(false)
      }
    },
    [accountId],
  )

  const pollWindowLogin = useCallback(
    async (id: string, gen: number) => {
      try {
        const status = await api.loginStatus(id)
        if (gen !== sessionGenRef.current) return
        setWindowStatus(status)
        if (
          status.state === 'succeeded' ||
          status.state === 'failed' ||
          status.state === 'expired'
        ) {
          if (status.state === 'succeeded') {
            await onLoginSucceeded()
          }
          return
        }
        if (gen !== sessionGenRef.current) return
        winTimerRef.current = window.setTimeout(() => {
          void pollWindowLogin(id, gen)
        }, 2000)
      } catch (err) {
        if (gen !== sessionGenRef.current) return
        setError(err instanceof ApiError ? err.message : '读取浏览器登录状态失败')
      }
    },
    [onLoginSucceeded],
  )

  const startWindow = useCallback(async () => {
    if (!accountId) return
    clearTimers()
    const gen = sessionGenRef.current
    setError(null)
    try {
      const status = await api.startLogin(accountId)
      if (gen !== sessionGenRef.current) return
      setWindowStatus(status)
      void pollWindowLogin(accountId, gen)
    } catch (err) {
      if (gen !== sessionGenRef.current) return
      setError(err instanceof ApiError ? err.message : '拉起本机浏览器登录失败')
    }
  }, [accountId, clearTimers, pollWindowLogin])

  const cancelAllLogin = useCallback(async () => {
    if (!accountId) return
    clearTimers()
    try {
      const status = await api.cancelLogin(accountId)
      setQrStatus(status)
      setWindowStatus(status)
      replaceBlobUrl(null)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '取消登录流程失败')
    }
  }, [accountId, clearTimers, replaceBlobUrl])

  return {
    qrStatus,
    qrBlobUrl,
    verifyOptions,
    verifyAutoOption,
    verifyBusy,
    verifyFeedback,
    windowStatus,
    error,
    startQr,
    selectVerifyOption,
    submitVerifyCode,
    startWindow,
    cancelAllLogin,
  }
}
