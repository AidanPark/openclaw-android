import { useState, useEffect, useCallback } from 'react'
import { useRoute } from '../lib/router'
import { bridge } from '../lib/bridge'
import { useNativeEvent } from '../lib/useNativeEvent'
import { t } from '../i18n'

interface Platform { id: string; name: string; icon: string; desc: string }

export function SettingsPlatforms() {
  const { navigate } = useRoute()
  const [available, setAvailable] = useState<Platform[]>([])
  const [active, setActive] = useState('')
  const [installing, setInstalling] = useState<string | null>(null)
  const [progress, setProgress] = useState(0)

  useEffect(() => {
    const data = bridge.callJson<Platform[]>('getAvailablePlatforms')
    if (data) setAvailable(data)

    const ap = bridge.callJson<{ id: string }>('getActivePlatform')
    if (ap) setActive(ap.id)
  }, [])

  const onProgress = useCallback((data: unknown) => {
    const d = data as { target?: string; progress?: number }
    if (d.progress !== undefined) setProgress(d.progress)
    if (d.progress !== undefined && d.progress >= 1) {
      if (d.target) setActive(d.target)
      setInstalling(null)
    }
  }, [])
  useNativeEvent('install_progress', onProgress)

  function handleInstall(id: string) {
    setInstalling(id)
    setProgress(0)
    bridge.call('installPlatform', id)
  }

  return (
    <div className="page">
      <div className="page-header">
        <button className="back-btn" onClick={() => navigate('/settings')}>←</button>
        <div className="page-title">{t('platforms_title')}</div>
      </div>

      {/* Progreso de instalación */}
      {installing && (
        <div className="card" style={{ marginBottom: 16 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10 }}>
            <div className="spinner" />
            <div style={{ fontSize: 14, fontWeight: 600 }}>
              {t('platforms_installing', { name: installing })}
            </div>
          </div>
          <div className="progress-bar">
            <div className="progress-fill" style={{ width: `${Math.round(progress * 100)}%` }} />
          </div>
        </div>
      )}

      {available.map(p => {
        const isActive = p.id === active
        return (
          <div key={p.id} className={`card${!isActive ? ' clickable' : ''}`}>
            <div className="card-row">
              {/* Icono de plataforma */}
              <div className="card-icon">
                {p.icon.startsWith('/') ? (
                  <img src={p.icon.replace(/^\//, './')} alt={p.name}
                    style={{ width: 28, height: 28, borderRadius: 4 }} />
                ) : (
                  <span style={{ fontSize: 22 }}>{p.icon}</span>
                )}
              </div>

              <div className="card-content">
                <div className="card-label" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  {p.name}
                  {isActive && (
                    <span className="pill pill-success">{t('platforms_active')}</span>
                  )}
                </div>
                <div className="card-desc">{p.desc}</div>
              </div>

              {!isActive && (
                <button
                  className="btn btn-primary btn-sm"
                  onClick={() => handleInstall(p.id)}
                  disabled={installing !== null}
                >
                  {installing === p.id ? '...' : t('platforms_install')}
                </button>
              )}
            </div>
          </div>
        )
      })}
    </div>
  )
}
