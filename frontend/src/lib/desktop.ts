/**
 * Tauri v2 桌面运行时桥接层（零外部依赖，浏览器/Docker 环境自动静默降级）。
 */

export interface DesktopBootstrap {
  desktopMode: boolean
  apiBaseUrl: string
  desktopToken: string
  dataDir: string
  version: string
}

interface TauriInternals {
  invoke?: <T = unknown>(cmd: string, args?: Record<string, unknown>) => Promise<T>
}

function getTauriInvoke(): TauriInternals['invoke'] | null {
  if (typeof window === 'undefined') return null
  const internals = (window as unknown as { __TAURI_INTERNALS__?: TauriInternals })
    .__TAURI_INTERNALS__
  return typeof internals?.invoke === 'function' ? internals.invoke : null
}

export function isTauriDesktop(): boolean {
  return getTauriInvoke() !== null
}

export async function loadDesktopBootstrap(): Promise<DesktopBootstrap | null> {
  const invoke = getTauriInvoke()
  if (!invoke) return null
  return invoke<DesktopBootstrap>('get_desktop_bootstrap')
}

export async function getDesktopAutostart(): Promise<boolean> {
  const invoke = getTauriInvoke()
  if (!invoke) return false
  return Boolean(await invoke<boolean>('get_autostart'))
}

export async function setDesktopAutostart(enabled: boolean): Promise<boolean> {
  const invoke = getTauriInvoke()
  if (!invoke) return false
  return Boolean(await invoke<boolean>('set_autostart', { enabled }))
}

export async function openDesktopDataDir(): Promise<void> {
  const invoke = getTauriInvoke()
  if (!invoke) return
  await invoke('open_data_dir')
}
