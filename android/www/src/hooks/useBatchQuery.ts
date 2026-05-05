/**
 * useBatchQuery - Hook para ejecutar múltiples consultas al bridge de forma eficiente.
 * 
 * Utiliza el método batchCall del bridge para reducir overhead de comunicación
 * entre el frontend y el código nativo.
 */

import { useState, useCallback } from 'react'
import { bridge, type OpenClawBridge } from '../lib/bridge'

interface BatchQueryResult<T = unknown> {
    method: string
    data: T | null
    success: boolean
    error?: string
}

interface UseBatchQueryReturn {
    loading: boolean
    error: string | null
    execute: <T>(methods: string[]) => Promise<BatchQueryResult<T>[]>
    executeSingle: <T>(method: string) => Promise<BatchQueryResult<T>>
}

export function useBatchQuery(): UseBatchQueryReturn {
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState<string | null>(null)

    const execute = useCallback(async <T,>(methods: string[]): Promise<BatchQueryResult<T>[]> => {
        if (!bridge.isAvailable()) {
            setError('Bridge not available')
            return methods.map(m => ({
                method: m,
                data: null,
                success: false,
                error: 'Bridge not available'
            }))
        }

        setLoading(true)
        setError(null)

        try {
            const results = await bridge.batchCall<T>(methods as any)
            return results.map(r => ({
                method: r.method,
                data: r.data,
                success: r.success,
                error: r.success ? undefined : 'Query failed',
            }))
        } catch (err) {
            const errorMsg = err instanceof Error ? err.message : 'Unknown error'
            setError(errorMsg)
            return methods.map(m => ({
                method: m,
                data: null,
                success: false,
                error: errorMsg
            }))
        } finally {
            setLoading(false)
        }
    }, [])

    const executeSingle = useCallback(async <T,>(method: string): Promise<BatchQueryResult<T>> => {
        const results = await execute<T>([method])
        return results[0] || { method, data: null, success: false, error: 'No result' }
    }, [execute])

    return {
        loading,
        error,
        execute,
        executeSingle,
    }
}