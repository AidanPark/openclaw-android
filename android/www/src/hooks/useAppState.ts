/**
 * useAppState - Hook para acceder al estado centralizado de la app.
 * 
 * Proporciona acceso al estado global sin necesidad de usar useAppContext directamente.
 * Maneja automáticamente el refresh cuando el componente se monta.
 */

import { useEffect } from 'react'
import { useAppContext, type SetupStatus, type EnvInfo, type StorageInfo, type SessionInfo, type InstalledTool } from '../contexts/AppContext'

export interface UseAppStateReturn {
    // Loading
    loading: boolean

    // Setup
    setupStatus: SetupStatus | null
    isInstalled: boolean

    // Environment
    envInfo: EnvInfo
    nodeVersion: string
    npmVersion: string
    gitVersion: string
    openclawVersion: string

    // Storage
    storageInfo: StorageInfo | null

    // Sessions
    sessions: SessionInfo[]
    activeSessionId: string

    // Tools
    installedTools: InstalledTool[]

    // Error handling
    error: string | null
    clearError: () => void

    // Actions
    refresh: (showSpinner?: boolean) => Promise<void>
    batchRefresh: () => Promise<void>
}

export function useAppState(): UseAppStateReturn {
    const context = useAppContext()

    // Auto-refresh on mount if still loading after a delay
    useEffect(() => {
        if (context.loading) {
            const timer = setTimeout(() => {
                context.refresh()
            }, 1000)
            return () => clearTimeout(timer)
        }
    }, [])

    // Derived values for convenience
    const nodeVersion = context.envInfo.node?.version
        ?? (context.setupStatus?.bootstrapInstalled ? 'detected' : 'not detected')

    const npmVersion = context.envInfo.npm?.version ?? 'not detected'
    const gitVersion = context.envInfo.git?.version ?? 'not detected'
    const openclawVersion = context.envInfo.openclaw?.version
        ?? (context.setupStatus?.openclawReady ? 'detected' : 'not detected')

    return {
        ...context,
        nodeVersion,
        npmVersion,
        gitVersion,
        openclawVersion,
    }
}