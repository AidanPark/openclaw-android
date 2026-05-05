import { describe, it, expect, beforeEach } from 'vitest'
import { render, screen, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Router, Route, useRoute } from '../lib/router'

// ── Helpers ────────────────────────────────────────────────────────────────

function setHash(hash: string) {
    window.location.hash = hash
    window.dispatchEvent(new HashChangeEvent('hashchange'))
}

function TestNav() {
    const { path, navigate } = useRoute()
    return (
        <div>
            <span data-testid="path">{path}</span>
            <button onClick={() => navigate('/dashboard')}>Dashboard</button>
            <button onClick={() => navigate('/settings')}>Settings</button>
        </div>
    )
}

// ── Tests ──────────────────────────────────────────────────────────────────

describe('Router', () => {
    beforeEach(() => {
        window.location.hash = ''
    })

    it('renders children', () => {
        render(<Router><div data-testid="child">hello</div></Router>)
        expect(screen.getByTestId('child')).toBeInTheDocument()
    })

    it('provides current path via useRoute', () => {
        window.location.hash = '#/dashboard'
        render(<Router><TestNav /></Router>)
        expect(screen.getByTestId('path').textContent).toBe('/dashboard')
    })

    it('defaults to /dashboard when hash is empty', () => {
        window.location.hash = ''
        render(<Router><TestNav /></Router>)
        expect(screen.getByTestId('path').textContent).toBe('/dashboard')
    })

    it('navigate() updates the path', async () => {
        render(<Router><TestNav /></Router>)
        await userEvent.click(screen.getByText('Settings'))
        expect(screen.getByTestId('path').textContent).toBe('/settings')
    })

    it('reacts to hashchange events', async () => {
        render(<Router><TestNav /></Router>)
        await act(async () => { setHash('#/settings') })
        expect(screen.getByTestId('path').textContent).toBe('/settings')
    })
})

describe('Route', () => {
    it('renders children when path matches exactly', () => {
        window.location.hash = '#/dashboard'
        render(
            <Router>
                <Route path="/dashboard"><span>Dashboard content</span></Route>
                <Route path="/settings"><span>Settings content</span></Route>
            </Router>
        )
        expect(screen.getByText('Dashboard content')).toBeInTheDocument()
        expect(screen.queryByText('Settings content')).toBeNull()
    })

    it('renders children when path is a prefix match', () => {
        window.location.hash = '#/settings/tools'
        render(
            <Router>
                <Route path="/settings"><span>Settings content</span></Route>
            </Router>
        )
        expect(screen.getByText('Settings content')).toBeInTheDocument()
    })

    it('does not render when path does not match', () => {
        window.location.hash = '#/dashboard'
        render(
            <Router>
                <Route path="/settings"><span>Settings content</span></Route>
            </Router>
        )
        expect(screen.queryByText('Settings content')).toBeNull()
    })
})
