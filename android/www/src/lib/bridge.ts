/**
 * JsBridge wrapper — typed interface to window.OpenClaw (§2.6).
 * All Kotlin @JavascriptInterface methods return JSON strings.
 */

export interface OpenClawBridge {
  // ── View ──────────────────────────────────────
  showTerminal(): void
  showWebView(): void

  // ── Terminal sessions ─────────────────────────
  createSession(): string
  switchSession(id: string): void
  closeSession(id: string): void
  getTerminalSessions(): string
  writeToTerminal(id: string, data: string): void
  runInNewSession(command: string): void

  // ── Setup / installation ──────────────────────
  getSetupStatus(): string
  getBootstrapStatus(): string
  getAppFilesDir(): string
  startSetup(mode?: string): void
  hasPayloadAsset(): string
  pickPayloadFile(): void
  /** Install from pre-built rootfs asset (no network). Emits setup_progress events. */
  startRootfsInstall(): void
  /** Returns rootfs installation status: extracted, initialized, openclawInstalled, wwwInstalled */
  getRootfsStatus(): string
  saveToolSelections(json: string): void
  saveInstallPath(path: string): void

  // ── Platforms ─────────────────────────────────
  getAvailablePlatforms(): string
  getInstalledPlatforms(): string
  installPlatform(id: string): void
  uninstallPlatform(id: string): void
  switchPlatform(id: string): void
  getActivePlatform(): string

  // ── Tools ─────────────────────────────────────
  getInstalledTools(): string
  installTool(id: string): void
  uninstallTool(id: string): void
  isToolInstalled(id: string): string

  // ── Environment ───────────────────────────────
  getEnvironmentInfo(): string

  // ── Commands ──────────────────────────────────
  runCommand(cmd: string): string
  runCommandAsync(callbackId: string, cmd: string): void
  testGrunNode(): string
  launchGateway(): void

  // ── Updates ───────────────────────────────────
  checkForUpdates(): string
  applyUpdate(component: string): void
  getApkUpdateInfo(): string

  // ── App info ──────────────────────────────────
  getAppInfo(): string

  // ── System ────────────────────────────────────
  getBatteryOptimizationStatus(): string
  requestBatteryOptimizationExclusion(): void
  openSystemSettings(page: string): void
  copyToClipboard(text: string): void
  getStorageInfo(): string
  clearCache(): void
  openUrl(url: string): void
  /** Fix executable permissions on .sh scripts and wrappers in the app sandbox. */
  fixScriptPermissions(): string
  /** Get detailed version info for node, npm, openclaw, glibc. */
  getDetailedVersionInfo(): string
  /** Get version info (alias used by SetupBridge). */
  getVersionInfo(): string

  // ── Batch queries ─────────────────────────────
  /** Execute multiple queries in one call. Results emitted via native:batch_result event. */
  batchQuery(callbackId: string, requests: string): void
}

declare global {
  interface Window {
    OpenClaw?: OpenClawBridge
    __oc?: { emit(type: string, data: unknown): void }
  }
}

export function isAvailable(): boolean {
  return typeof window.OpenClaw !== 'undefined'
}

export function call<K extends keyof OpenClawBridge>(
  method: K,
  ...args: Parameters<OpenClawBridge[K]>
): ReturnType<OpenClawBridge[K]> | null {
  if (window.OpenClaw && typeof window.OpenClaw[method] === 'function') {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    return (window.OpenClaw[method] as (...a: any[]) => any)(...args)
  }
  console.warn('[bridge] OpenClaw not available:', method)
  return null
}

export function callJson<T>(
  method: keyof OpenClawBridge,
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  ...args: any[]
): T | null {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const raw = (call as any)(method, ...args)
  if (raw == null) return null
  try {
    return JSON.parse(raw as string) as T
  } catch {
    return raw as unknown as T
  }
}

/**
 * Execute multiple queries in a single bridge call.
 * Much more efficient than making multiple individual calls.
 * 
 * @param methods Array of method names to execute
 * @returns Promise with array of results in the same order
 */
export async function batchCall<T = unknown>(
  methods: Array<keyof OpenClawBridge>
): Promise<Array<{ method: string; data: T; success: boolean }>> {
  return new Promise((resolve) => {
    if (!isAvailable()) {
      resolve(methods.map(m => ({
        method: m,
        data: null as T,
        success: false
      })))
      return
    }

    const callbackId = `batch_${Date.now()}`

    const handler = (e: Event) => {
      const detail = (e as CustomEvent).detail as {
        callbackId: string
        results?: Array<{ method: string; result: string; success: boolean }>
        error?: string
      }

      if (detail.callbackId !== callbackId) return

      const results = (detail.results ?? []).map(r => ({
        method: r.method,
        data: r.success ? JSON.parse(r.result) : null,
        success: r.success,
      }))

      resolve(results)
      window.removeEventListener('native:batch_result', handler)
    }

    window.addEventListener('native:batch_result', handler)

    // Call the batchQuery method on the bridge
    call('batchQuery', callbackId, JSON.stringify(methods))

    // Timeout after 10 seconds
    setTimeout(() => {
      window.removeEventListener('native:batch_result', handler)
      resolve(methods.map(m => ({
        method: m,
        data: null as T,
        success: false
      })))
    }, 10000)
  })
}

export const bridge = { isAvailable, call, callJson, batchCall }
