import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { useNativeEvent } from '../lib/useNativeEvent'
import { useAppState } from '../hooks/useAppState'
import { AppProvider } from '../contexts/AppContext'

// ── useNativeEvent ─────────────────────────────────────────────────────────

describe('useNativeEvent', () => {
    it('calls handler when native event fires', () => {
        const handler = vi.fn()

        function TestComponent() {
            useNativeEvent('test_event', handler)
            return <div>test</div>
        }

        render(<TestComponent />)
        window.dispatchEvent(new CustomEvent('native:test_event', { detail: { foo: 'bar' } }))
        expect(handler).toHaveBeenCalledWith({ foo: 'bar' })
    })

    it('does not call handler after unmount', () => {
        const handler = vi.fn()

        function TestComponent() {
            useNativeEvent('unmount_test', handler)
            return <div>test</div>
        }

        const { unmount } = render(<TestComponent />)
        unmount()
        window.dispatchEvent(new CustomEvent('native:unmount_test', { detail: {} }))
        expect(handler).not.toHaveBeenCalled()
    })

    it('handles multiple event types independently', () => {
        const handlerA = vi.fn()
        const handlerB = vi.fn()

        function TestComponent() {
            useNativeEvent('event_a', handlerA)
            useNativeEvent('event_b', handlerB)
            return <div>test</div>
        }

        render(<TestComponent />)
        window.dispatchEvent(new CustomEvent('native:event_a', { detail: 'A' }))
        expect(handlerA).toHaveBeenCalledWith('A')
        expect(handlerB).not.toHaveBeenCalled()

        window.dispatchEvent(new CustomEvent('native:event_b', { detail: 'B' }))
        expect(handlerB).toHaveBeenCalledWith('B')
    })
})

// ── useAppState ────────────────────────────────────────────────────────────

function mockBridge() {
    ; (window as any).OpenClaw = {
        getSetupStatus: () => JSON.stringify({
            bootstrapInstalled: true, platformInstalled: true,
            runtimeInstalled: true, wwwInstalled: true,
            source: 'payload', prootReady: false, rootfsReady: false, openclawReady: true,
        }),
        getEnvironmentInfo: () => JSON.stringify({
            node: { detected: true, version: 'v22.0.0' },
            npm: { detected: true, version: '10.5.0' },
            git: { detected: false },
            openclaw: { detected: true, version: '2.1.0' },
        }),
        getStorageInfo: () => JSON.stringify({ totalMb: 128000, freeMb: 64000, usedMb: 64000 }),
        getTerminalSessions: () => JSON.stringify([]),
        getInstalledTools: () => JSON.stringify([]),
        batchQuery: vi.fn(),
    }
}

function TestStateConsumer() {
    const state = useAppState()
    return (
        <div>
            <span data-testid="loading">{String(state.loading)}</span>
            <span data-testid="installed">{String(state.isInstalled)}</span>
            <span data-testid="node">{state.nodeVersion}</span>
            <span data-testid="npm">{state.npmVersion}</span>
            <span data-testid="git">{state.gitVersion}</span>
            <span data-testid="openclaw">{state.openclawVersion}</span>
        </div>
    )
}

describe('useAppState', () => {
    beforeEach(() => mockBridge())
    afterEach(() => { ; (window as any).OpenClaw = undefined })

    it('provides derived version strings', async () => {
        render(
            <AppProvider>
                <TestStateConsumer />
            </AppProvider>
        )
        await waitFor(() => {
            expect(screen.getByTestId('loading').textContent).toBe('false')
        })
        expect(screen.getByTestId('node').textContent).toBe('v22.0.0')
        expect(screen.getByTestId('npm').textContent).toBe('10.5.0')
        expect(screen.getByTestId('openclaw').textContent).toBe('2.1.0')
    })

    it('shows not detected for missing components', async () => {
        render(
            <AppProvider>
                <TestStateConsumer />
            </AppProvider>
        )
        await waitFor(() => {
            expect(screen.getByTestId('loading').textContent).toBe('false')
        })
        expect(screen.getByTestId('git').textContent).toBe('not detected')
    })

    it('shows bootstrapInstalled fallback for node when envInfo missing', async () => {
        ; (window as any).OpenClaw.getEnvironmentInfo = () => JSON.stringify({})
        render(
            <AppProvider>
                <TestStateConsumer />
            </AppProvider>
        )
        await waitFor(() => {
            expect(screen.getByTestId('loading').textContent).toBe('false')
        })
        // bootstrapInstalled=true → fallback is 'detected'
        expect(screen.getByTestId('node').textContent).toBe('detected')
    })
})
