/**
 * AppContext - centralized state management for OpenClaw React frontend.
 * 
 * Provides a single source of truth for:
 * - Setup/installation status
 * - Environment info (node, npm, git, openclaw versions)
 * - Storage info
 * - Terminal sessions
 * - Installed tools
 * 
 * Automatically refreshes on native events from Kotlin bridge.
 */

import React, {
    createContext,
    useContext,
    useState,
    useEffect,
    useCallback,
    useMemo,
    type ReactNode
} from 'react'
import { bridge } from '../lib/bridge'
import { useNativeEvent } from '../lib/useNativeEvent'

// ─────────────────────────────────────────────────────────────────────────
// Types
// ─────────────────────────────────────────────────────────────────────────

export interface SetupStatus {
    bootstrapInstalled: boolean
    runtimeInstalled: boolean
    wwwInstalled: boolean
    platformInstalled: boolean
    source: string
    prootReady: boolean
    rootfsReady: boolean
    openclawReady: boolean
}

export interface EnvComponent {
    version?: string
    detected: boolean
    path?: string
}

export interface EnvInfo {
    node?: EnvComponent
    npm?: EnvComponent
    git?: EnvComponent
    openclaw?: EnvComponent
    prefix?: string
    home?: string
}

export interface StorageInfo {
    total: number
    free: number
    used: number
    totalMb: number
    freeMb: number
    usedMb: number
    appUsedBytes: number
    appUsedMb: number
}

export interface SessionInfo {
    id: string
    name: string
    active: boolean
    finished: boolean
}

export interface InstalledTool {
    id: string
    name: string
    version?: string
}

export interface AppState {
    // Loading state
    loading: boolean

    // Setup status
    setupStatus: SetupStatus | null
    isInstalled: boolean

    // Environment
    envInfo: EnvInfo
    storageInfo: StorageInfo | null

    // Terminal sessions
    sessions: SessionInfo[]
    activeSessionId: string

    // Tools
    installedTools: InstalledTool[]

    // Errors
    error: string | null
}

interface AppContextValue extends AppState {
    refresh: (showSpinner?: boolean) => Promise<void>
    batchRefresh: () => Promise<void>
    clearError: () => void
}

// ─────────────────────────────────────────────────────────────────────────
// Context
// ─────────────────────────────────────────────────────────────────────────

const AppContext = createContext<AppContextValue | null>(null)

export function useAppContext(): AppContextValue {
    const ctx = useContext(AppContext)
    if (!ctx) {
        throw new Error('useAppContext must be used within AppProvider')
    }
    return ctx
}

// ─────────────────────────────────────────────────────────────────────────
// Provider
// ─────────────────────────────────────────────────────────────────────────

interface Props {
    children: ReactNode
}

export function AppProvider({ children }: Props) {
    const [state, setState] = useState<AppState>({
        loading: true,
        setupStatus: null,
        isInstalled: false,
        envInfo: {},
        storageInfo: null,
        sessions: [],
        activeSessionId: '',
        installedTools: [],
        error: null,
    })

    // ── Refresh method ───────────────────────────────────────────────────

    const refresh = useCallback(async (showSpinner = false) => {
        if (!bridge.isAvailable()) {
            setState(s => ({ ...s, loading: false }))
            return
        }

        try {
            // Fetch all data in parallel for performance
            const [setupStatus, envInfo, storageInfo, sessions, tools] = await Promise.all([
                Promise.resolve(bridge.callJson<SetupStatus>('getSetupStatus')),
                Promise.resolve(bridge.callJson<EnvInfo>('getEnvironmentInfo')),
                Promise.resolve(bridge.callJson<StorageInfo>('getStorageInfo')),
                Promise.resolve(bridge.callJson<SessionInfo[]>('getTerminalSessions')),
                Promise.resolve(bridge.callJson<InstalledTool[]>('getInstalledTools')),
            ])

            const activeSession = sessions?.find(s => s.active)

            setState(s => ({
                ...s,
                loading: false,
                setupStatus: setupStatus ?? null,
                isInstalled: !!(setupStatus?.bootstrapInstalled && setupStatus?.platformInstalled),
                envInfo: envInfo ?? {},
                storageInfo: storageInfo ?? null,
                sessions: sessions ?? [],
                activeSessionId: activeSession?.id ?? '',
                installedTools: tools ?? [],
                error: null,
            }))
        } catch (err) {
            setState(s => ({
                ...s,
                loading: false,
                error: err instanceof Error ? err.message : 'Unknown error'
            }))
        }
    }, [])

    // ── Batch refresh using batchQuery ───────────────────────────────────

    const batchRefresh = useCallback(async () => {
        if (!bridge.isAvailable()) return

        try {
            const callbackId = `batch_${Date.now()}`

            const handler = (e: Event) => {
                const data = (e as CustomEvent).detail as {
                    callbackId: string
                    results: Array<{ method: string; result: string; success: boolean }>
                }

                if (data.callbackId !== callbackId) return

                const results = data.results ?? []

                // Update state based on results
                const statusResult = results.find(r => r.method === 'getSetupStatus')
                if (statusResult?.success) {
                    const status = JSON.parse(statusResult.result)
                    setState(s => ({
                        ...s,
                        setupStatus: status,
                        isInstalled: !!(status.bootstrapInstalled && status.platformInstalled)
                    }))
                }

                const envResult = results.find(r => r.method === 'getEnvironmentInfo')
                if (envResult?.success) {
                    setState(s => ({ ...s, envInfo: JSON.parse(envResult.result) }))
                }

                window.removeEventListener('native:batch_result', handler)
            }

            window.addEventListener('native:batch_result', handler)

            // Call batchQuery with desired methods
            bridge.call('batchQuery', callbackId, JSON.stringify([
                'getSetupStatus',
                'getEnvironmentInfo',
                'getStorageInfo',
                'getInstalledTools',
            ]))

            // Timeout fallback
            setTimeout(() => {
                window.removeEventListener('native:batch_result', handler)
            }, 10000)

        } catch (err) {
            console.error('batchRefresh failed:', err)
            await refresh()
        }
    }, [refresh])

    // ── Clear error ──────────────────────────────────────────────────────

    const clearError = useCallback(() => {
        setState(s => ({ ...s, error: null }))
    }, [])

    // ── Effects ──────────────────────────────────────────────────────────

    // Initial load
    useEffect(() => {
        refresh()
    }, [refresh])

    // Listen to native events for auto-refresh
    useNativeEvent('session_changed', () => refresh())
    useNativeEvent('setup_progress', (data) => {
        const d = data as { progress?: number }
        if (d.progress === 1) {
            setTimeout(() => refresh(), 500)
        }
    })
    useNativeEvent('install_progress', () => refresh())

    // ── Memoized value ───────────────────────────────────────────────────

    const value = useMemo<AppContextValue>(() => ({
        ...state,
        refresh,
        batchRefresh,
        clearError,
    }), [state, refresh, batchRefresh, clearError])

    return (
        <AppContext.Provider value={value}>
            {children}
        </AppContext.Provider>
    )
}