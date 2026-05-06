import { useState, useEffect } from 'react'
import { useRoute } from '../lib/router'
import { bridge } from '../lib/bridge'
import { t } from '../i18n'

interface UpdateInfo {
  currentVersion?: string
  updateUrl?: string
  updateAvailable?: boolean
}

export function SettingsUpdates() {
  const { navigate } = useRoute()
  const [info, setInfo] = useState<UpdateInfo | null>(null)
  const [checking, setChecking] = useState(true)
  const [updating, setUpdating] = useState(false)

  useEffect(() => {
    try {
      // checkForUpdates returns {currentVersion, updateUrl} — NOT an array
      const data = bridge.callJson<UpdateInfo>('checkForUpdates')
      setInfo(data || {})
    } catch {
      setInfo({})
    }
    setChecking(false)
  }, [])

  function handleOpenReleasePage() {
    const url = info?.updateUrl || 'https://github.com/AidanPark/openclaw-android/releases/latest'
    bridge.call('openUrl', url)
  }

  function handleUpdateOpenClaw() {
    setUpdating(true)
    bridge.call('showTerminal')
    const sessions = bridge.callJson<Array<{ id: string; active: boolean }>>('getTerminalSessions')
    const active = sessions?.find(s => s.active)
    const id = active?.id || bridge.callJson<{ id: string }>('createSession')?.id || ''
    if (id) {
      setTimeout(() => {
        bridge.call('writeToTerminal', id, 'openclaw update\n')
        setUpdating(false)
      }, 300)
    } else {
      setUpdating(false)
    }
  }

  return (
    <div className="page">
      <div className="page-header">
        <button className="back-btn" onClick={() => navigate('/settings')}>←</button>
        <div className="page-title">{t('updates_title')}</div>
      </div>

      {checking && (
        <div className="empty-state">
          <div className="spinner" />
          <div className="empty-state-text">{t('updates_checking')}</div>
        </div>
      )}

      {!checking && (
        <>
          {/* APK version */}
          <div className="section-title">APK</div>
          <div className="card">
            <div className="info-row">
              <span className="label">{t('about_version')}</span>
              <span style={{ fontFamily: 'monospace', fontWeight: 600 }}>
                {info?.currentVersion || '—'}
              </span>
            </div>
            <div style={{ marginTop: 12 }}>
              <button
                className="btn btn-secondary btn-sm"
                onClick={handleOpenReleasePage}
              >
                ↗ {t('about_check_apk')}
              </button>
            </div>
          </div>

          {/* OpenClaw update */}
          <div className="section-title">OpenClaw</div>
          <div className="card">
            <div style={{ fontSize: 13, color: 'var(--text-secondary)', marginBottom: 12, lineHeight: 1.6 }}>
              Para actualizar OpenClaw ejecuta <code style={{ fontFamily: 'monospace', background: 'var(--bg-tertiary)', padding: '1px 6px', borderRadius: 4 }}>openclaw update</code> en el terminal.
            </div>
            <button
              className="btn btn-primary"
              style={{ width: '100%' }}
              onClick={handleUpdateOpenClaw}
              disabled={updating}
            >
              {updating
                ? <><span className="spinner" style={{ width: 16, height: 16, marginRight: 8 }} />Abriendo terminal...</>
                : '▶ Actualizar OpenClaw en terminal'}
            </button>
          </div>

          {/* Estado */}
          <div className="section-title">Estado</div>
          <div className="card">
            <div className="empty-state" style={{ padding: '20px 0' }}>
              <div style={{ fontSize: 28 }}>✓</div>
              <div className="empty-state-text">{t('updates_up_to_date')}</div>
            </div>
          </div>
        </>
      )}
    </div>
  )
}
