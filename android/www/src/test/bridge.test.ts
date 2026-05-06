import { describe, it, expect, beforeEach, vi } from 'vitest'
import { isAvailable, call, callJson, batchCall, bridge } from '../lib/bridge'

// ── Helpers ────────────────────────────────────────────────────────────────

function mockBridge(overrides: Partial<Record<string, unknown>> = {}) {
    ; (window as any).OpenClaw = {
        getSetupStatus: () => JSON.stringify({ bootstrapInstalled: true, platformInstalled: true }),
        getBootstrapStatus: () => JSON.stringify({ installed: true }),
        getStorageInfo: () => JSON.stringify({ totalMb: 64000, freeMb: 32000, usedMb: 32000 }),
        getEnvironmentInfo: () => JSON.stringify({ node: { detected: true, version: 'v20.0.0' } }),
        getTerminalSessions: () => JSON.stringify([{ id: 'abc', active: true }]),
        getInstalledTools: () => JSON.stringify([{ id: 'tmux', name: 'tmux' }]),
        showTerminal: vi.fn(),
        showWebView: vi.fn(),
        batchQuery: vi.fn((callbackId: string, requests: string) => {
            const methods: string[] = JSON.parse(requests)
            const results = methods.map(method => ({
                method,
                result: (window as any).OpenClaw[method]?.() ?? '{}',
                success: true,
            }))
            // Simulate async native event
            setTimeout(() => {
                window.dispatchEvent(new CustomEvent('native:batch_result', {
                    detail: { callbackId, results },
                }))
            }, 0)
        }),
        ...overrides,
    }
}

function clearBridge() {
    ; (window as any).OpenClaw = undefined
}

// ── Tests ──────────────────────────────────────────────────────────────────

describe('bridge.isAvailable()', () => {
    it('returns false when OpenClaw is not injected', () => {
        clearBridge()
        expect(isAvailable()).toBe(false)
    })

    it('returns true when OpenClaw is injected', () => {
        mockBridge()
        expect(isAvailable()).toBe(true)
        clearBridge()
    })
})

describe('bridge.call()', () => {
    beforeEach(() => mockBridge())
    afterEach(() => clearBridge())

    it('calls the method and returns its value', () => {
        const result = call('getSetupStatus')
        expect(result).toContain('bootstrapInstalled')
    })

    it('returns null when bridge is unavailable', () => {
        clearBridge()
        expect(call('getSetupStatus')).toBeNull()
    })

    it('calls void methods without error', () => {
        expect(() => call('showTerminal')).not.toThrow()
    })
})

describe('bridge.callJson()', () => {
    beforeEach(() => mockBridge())
    afterEach(() => clearBridge())

    it('parses JSON response correctly', () => {
        const result = callJson<{ bootstrapInstalled: boolean }>('getSetupStatus')
        expect(result).not.toBeNull()
        expect(result?.bootstrapInstalled).toBe(true)
    })

    it('returns null when bridge is unavailable', () => {
        clearBridge()
        expect(callJson('getSetupStatus')).toBeNull()
    })

    it('returns raw value if not valid JSON', () => {
        ; (window as any).OpenClaw = { getSetupStatus: () => 'not-json' }
        const result = callJson('getSetupStatus')
        expect(result).toBe('not-json')
        clearBridge()
    })
})

describe('bridge.batchCall()', () => {
    beforeEach(() => mockBridge())
    afterEach(() => clearBridge())

    it('returns results for all requested methods', async () => {
        const results = await batchCall(['getSetupStatus', 'getBootstrapStatus'])
        expect(results).toHaveLength(2)
        expect(results[0].method).toBe('getSetupStatus')
        expect(results[0].success).toBe(true)
        expect(results[1].method).toBe('getBootstrapStatus')
    })

    it('returns failed results when bridge is unavailable', async () => {
        clearBridge()
        const results = await batchCall(['getSetupStatus'])
        expect(results[0].success).toBe(false)
        expect(results[0].data).toBeNull()
    })

    it('parses JSON data in results', async () => {
        const results = await batchCall<{ bootstrapInstalled: boolean }>(['getSetupStatus'])
        expect(results[0].data?.bootstrapInstalled).toBe(true)
    })
})

describe('bridge object export', () => {
    it('exports isAvailable, call, callJson, batchCall', () => {
        expect(typeof bridge.isAvailable).toBe('function')
        expect(typeof bridge.call).toBe('function')
        expect(typeof bridge.callJson).toBe('function')
        expect(typeof bridge.batchCall).toBe('function')
    })
})
