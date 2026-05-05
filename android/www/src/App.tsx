import { useState, useEffect, useCallback } from 'react'
import { Route, useRoute } from './lib/router'
import { bridge } from './lib/bridge'
import { useNativeEvent } from './lib/useNativeEvent'
import { t } from './i18n'
import { Setup } from './screens/Setup'
import { Dashboard } from './screens/Dashboard'
import { Settings } from './screens/Settings'
import { SettingsKeepAlive } from './screens/SettingsKeepAlive'
import { SettingsStorage } from './screens/SettingsStorage'
import { SettingsAbout } from './screens/SettingsAbout'
import { SettingsUpdates } from './screens/SettingsUpdates'
import { SettingsPlatforms } from './screens/SettingsPlatforms'
import { SettingsTools } from './screens/SettingsTools'
import { SettingsAdvanced } from './screens/SettingsAdvanced'

type Tab = 'terminal' | 'dashboard' | 'settings'

interface SetupStatus {
  bootstrapInstalled: boolean
  runtimeInstalled: boolean
  wwwInstalled: boolean
  platformInstalled: boolean
}

export function App() {
  const { path, navigate } = useRoute()
  const [hasUpdates, setHasUpdates] = useState(false)
  const [setupDone, setSetupDone] = useState<boolean | null>(null)

  useEffect(() => {
    const status = bridge.callJson<SetupStatus>('getSetupStatus')
    if (status) {
      setSetupDone(!!status.bootstrapInstalled && !!status.platformInstalled)
    } else {
      setSetupDone(true)
    }

    const updates = bridge.callJson<{ updateAvailable?: boolean }>('checkForUpdates')
    if (updates?.updateAvailable) setHasUpdates(true)
  }, [])

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

  useEffect(() => {
    if (path === '/') {
      navigate('/dashboard')
    }
  }, [path, navigate])

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
        <Setup onComplete={() => { setSetupDone(true); navigate('/dashboard') }} />
      </Route>
      <Route path="/dashboard">
        <Dashboard />
      </Route>
      <Route path="/settings">
        <SettingsRouter />
      </Route>
    </>
  )
}

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