import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, waitFor, act } from '@testing-library/react'
import { AppProvider, useAppContext } from '../contexts/AppContext'

// ── Mock bridge ────────────────────────────────────────────────────────────

function mockBridge() {
    ; (window as any).OpenClaw = {
        getSetupStatus: () => JSON.stringify({
            bootstrapInstalled: true,
            platformInstalled: true,
            runtimeInstalled: true,
            wwwInstalled: true,
            source: 'payload',
            prootReady: false,
            rootfsReady: false,
            openclawReady: true,
        }),
        getEnvironmentInfo: () => JSON.stringify({
            node: { detected: true, version: 'v20.0.0' },
            npm: { detected: true, version: '10.0.0' },
        }),
        getStorageInfo: () => JSON.stringify({
            total: 64000000000, free: 32000000000, used: 32000000000,
            totalMb: 64000, freeMb: 32000, usedMb: 32000,
            appUsedBytes: 500000000, appUsedMb: 500,
        }),
        getTerminalSessions: () => JSON.stringify([
            { id: 'session-1', name: 'Terminal 1', active: true, finished: false },
        ]),
        getInstalledTools: () => JSON.stringify([
            { id: 'tmux', name: 'tmux' },
        ]),
        batchQuery: vi.fn(),
    }
}

function clearBridge() {
    ; (window as any).OpenClaw = undefined
}

// ── Consumer component for testing context ─────────────────────────────────

function TestConsumer() {
    const ctx = useAppContext()
    return (
        <div>
            <span data-testid="loading">{String(ctx.loading)}</span>
            <span data-testid="installed">{String(ctx.isInstalled)}</span>
            <span data-testid="session-id">{ctx.activeSessionId}</span>
            <span data-testid="tools-count">{ctx.installedTools.length}</span>
            <span data-testid="node-version">{ctx.envInfo.node?.version ?? 'none'}</span>
            <button data-testid="refresh" onClick={() => ctx.refresh()}>Refresh</button>
        </div>
    )
}

// ── Tests ──────────────────────────────────────────────────────────────────

describe('AppContext', () => {
    beforeEach(() => mockBridge())
    afterEach(() => clearBridge())

    it('throws when used outside AppProvider', () => {
        // Suppress console.error for this test
        const spy = vi.spyOn(console, 'error').mockImplementation(() => { })
        expect(() => render(<TestConsumer />)).toThrow('useAppContext must be used within AppProvider')
        spy.mockRestore()
    })

    it('starts in loading state', () => {
        render(<AppProvider><TestConsumer /></AppProvider>)
        // Initially loading=true before first fetch completes
        expect(screen.getByTestId('loading').textContent).toBe('true')
    })

    it('loads setup status from bridge', async () => {
        render(<AppProvider><TestConsumer /></AppProvider>)
        await waitFor(() => {
            expect(screen.getByTestId('loading').textContent).toBe('false')
        })
        expect(screen.getByTestId('installed').textContent).toBe('true')
    })

    it('loads active session id', async () => {
        render(<AppProvider><TestConsumer /></AppProvider>)
        await waitFor(() => {
            expect(screen.getByTestId('session-id').textContent).toBe('session-1')
        })
    })

    it('loads installed tools', async () => {
        render(<AppProvider><TestConsumer /></AppProvider>)
        await waitFor(() => {
            expect(screen.getByTestId('tools-count').textContent).toBe('1')
        })
    })

    it('loads environment info', async () => {
        render(<AppProvider><TestConsumer /></AppProvider>)
        await waitFor(() => {
            expect(screen.getByTestId('node-version').textContent).toBe('v20.0.0')
        })
    })

    it('sets isInstalled=false when bridge unavailable', async () => {
        clearBridge()
        render(<AppProvider><TestConsumer /></AppProvider>)
        await waitFor(() => {
            expect(screen.getByTestId('loading').textContent).toBe('false')
        })
        expect(screen.getByTestId('installed').textContent).toBe('false')
    })

    it('refresh re-fetches data', async () => {
        render(<AppProvider><TestConsumer /></AppProvider>)
        await waitFor(() => {
            expect(screen.getByTestId('loading').textContent).toBe('false')
        })
            // Modify mock to return different data
            ; (window as any).OpenClaw.getInstalledTools = () => JSON.stringify([
                { id: 'tmux', name: 'tmux' },
                { id: 'git', name: 'git' },
            ])
        await act(async () => {
            screen.getByTestId('refresh').click()
        })
        await waitFor(() => {
            expect(screen.getByTestId('tools-count').textContent).toBe('2')
        })
    })

    it('auto-refreshes on native:setup_progress event with progress=1', async () => {
        render(<AppProvider><TestConsumer /></AppProvider>)
        await waitFor(() => {
            expect(screen.getByTestId('loading').textContent).toBe('false')
        })
            ; (window as any).OpenClaw.getInstalledTools = () => JSON.stringify([
                { id: 'tmux', name: 'tmux' },
                { id: 'code-server', name: 'code-server' },
            ])
        await act(async () => {
            window.dispatchEvent(new CustomEvent('native:setup_progress', {
                detail: { progress: 1, message: 'Done' },
            }))
            // Wait for the 500ms debounce
            await new Promise(r => setTimeout(r, 600))
        })
        await waitFor(() => {
            expect(screen.getByTestId('tools-count').textContent).toBe('2')
        })
    })
})
