import '@testing-library/jest-dom'

// Mock window.OpenClaw bridge (not available in jsdom)
Object.defineProperty(window, 'OpenClaw', {
    value: undefined,
    writable: true,
})

// Mock window.__oc event emitter
Object.defineProperty(window, '__oc', {
    value: {
        emit: (type: string, data: unknown) => {
            window.dispatchEvent(new CustomEvent(`native:${type}`, { detail: data }))
        },
    },
    writable: true,
})
