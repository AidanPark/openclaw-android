import { lazy, Suspense, useState, useCallback } from 'react'
import { Route, useRoute } from './lib/router'
import { bridge } from './lib/bridge'
import { useNativeEvent } from './lib/useNativeEvent'
import { t } from './i18n'
import { AppProvider, useAppContext } from './contexts/AppContext'
import { Setup } from './screens/Setup'
import { Dashboard } from './screens/Dashboard'
import { Settings } from './screens/Settings'

// Lazy-load settings sub-screens — they are rarely visited and add weight
const SettingsKeepAlive = lazy(() =>
  import('./screens/SettingsKeepAlive').then(m => ({ default: m.SettingsKeepAlive }))
)
const SettingsStorage = lazy(() =>
  import('./screens/SettingsStorage').then(m => ({ default: m.SettingsStorage }))
)
const SettingsAbout = lazy(() =>
  import('./screens/SettingsAbout').then(m => ({ default: m.SettingsAbout }))
)
const SettingsUpdates = lazy(() =>
  import('./screens/SettingsUpdates').then(m => ({ default: m.SettingsUpdates }))
)
const SettingsPlatforms = lazy(() =>
  import('./screens/SettingsPlatforms').then(m => ({ default: m.SettingsPlatforms }))
)
const SettingsTools = lazy(() =>
  import('./screens/SettingsTools').then(m => ({ default: m.SettingsTools }))
)
const SettingsAdvanced = lazy(() =>
  import('./screens/SettingsAdvanced').then(m => ({ default: m.SettingsAdvanced }))
)

type Tab = 'terminal' | 'dashboard' | 'settings'

// ── Spinner shown while lazy chunks load ──────────────────────────────────
function ScreenFallback() {
  return (
    <div style={{
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      height: 'calc(100dvh - 56px)',
    }}>
      <div className="spinner" style={{ width: 28, height: 28, borderWidth: 3 }} />
    </div>
  )
}

// ── Inner app — consumes AppContext ───────────────────────────────────────
function AppInner() {
  const { path, navigate } = useRoute()
  const { setupStatus } = useAppContext()
  const [hasUpdates, setHasUpdates] = useState(false)

  // Derive setupDone from context instead of a separate bridge call
  const setupDone = setupStatus
    ? !!(setupStatus.bootstrapInstalled && setupStatus.platformInstalled)
    : null

  const onUpdateAvailable = useCallback(() => setHasUpdates(true), [])
  useNativeEvent('update_available', onUpdateAvailable)

  const activeTab: Tab = path.startsWith('/settings') || path.startsWith('/setup')
    ? 'settings'
    : 'dashboard'

  function handleTabClick(tab: Tab) {
    if (tab === 'terminal') {
      bridge.call('showTerminal')
      return
    }
    bridge.call('showWebView')
    navigate(tab === 'dashboard' ? '/dashboard' : '/settings')
  }

  // Redirect root to dashboard
  if (path === '/') {
    navigate('/dashboard')
    return null
  }

  // Initial loading — context not yet resolved
  if (setupDone === null) {
    return (
      <div style={{
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        height: '100dvh', flexDirection: 'column', gap: 16,
      }}>
        <img src="./openclaw.svg" alt="OpenClaw" style={{ width: 56, height: 56, opacity: 0.6 }} />
        <div className="spinner" style={{ width: 28, height: 28, borderWidth: 3 }} />
      </div>
    )
  }

  return (
    <>
      <nav className="tab-bar" role="navigation" aria-label="Main navigation">
        <button
          className="tab-bar-item"
          onClick={() => handleTabClick('terminal')}
          aria-label={t('tab_terminal')}
        >
          <span className="tab-icon">⌨</span>
          <span className="tab-label">{t('tab_terminal')}</span>
        </button>
        <button
          className={`tab-bar-item${activeTab === 'dashboard' ? ' active' : ''}`}
          onClick={() => handleTabClick('dashboard')}
          aria-label={t('tab_dashboard')}
          aria-current={activeTab === 'dashboard' ? 'page' : undefined}
        >
          <span className="tab-icon">◈</span>
          <span className="tab-label">{t('tab_dashboard')}</span>
        </button>
        <button
          className={`tab-bar-item${activeTab === 'settings' ? ' active' : ''}`}
          onClick={() => handleTabClick('settings')}
          aria-label={t('tab_settings')}
          aria-current={activeTab === 'settings' ? 'page' : undefined}
        >
          <span className="tab-icon">⚙</span>
          <span className="tab-label">{t('tab_settings')}</span>
          {hasUpdates && <span className="badge" aria-label={t('settings_updates_badge')} />}
        </button>
      </nav>

      <Route path="/setup">
        <Setup onComplete={() => navigate('/dashboard')} />
      </Route>
      <Route path="/dashboard">
        <Dashboard />
      </Route>
      <Route path="/settings">
        <Suspense fallback={<ScreenFallback />}>
          <SettingsRouter />
        </Suspense>
      </Route>
    </>
  )
}

// ── Settings sub-router ───────────────────────────────────────────────────
function SettingsRouter() {
  const { path } = useRoute()
  if (path === '/settings/keep-alive') return <SettingsKeepAlive />
  if (path === '/settings/storage') return <SettingsStorage />
  if (path === '/settings/about') return <SettingsAbout />
  if (path === '/settings/updates') return <SettingsUpdates />
  if (path === '/settings/platforms') return <SettingsPlatforms />
  if (path === '/settings/tools') return <SettingsTools />
  if (path === '/settings/advanced') return <SettingsAdvanced />
  return <Settings />
}

// ── Root export — wraps everything in AppProvider ─────────────────────────
export function App() {
  return (
    <AppProvider>
      <AppInner />
    </AppProvider>
  )
}
