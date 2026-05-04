/**
 * JsBridge wrapper — typed interface to window.OpenClaw (§2.6).
 * All Kotlin @JavascriptInterface methods return JSON strings.
 */

interface OpenClawBridge {
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

export const bridge = { isAvailable, call, callJson }
