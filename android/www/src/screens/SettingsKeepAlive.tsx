import { useState, useEffect } from 'react'
import { useRoute } from '../lib/router'
import { bridge } from '../lib/bridge'
import { t } from '../i18n'

export function SettingsKeepAlive() {
  const { navigate } = useRoute()
  const [batteryExcluded, setBatteryExcluded] = useState(false)
  const [copied, setCopied] = useState(false)
  const [requesting, setRequesting] = useState(false)

  useEffect(() => {
    const status = bridge.callJson<{ isIgnoring: boolean }>('getBatteryOptimizationStatus')
    if (status) setBatteryExcluded(status.isIgnoring)
  }, [])

  const ppkCommand = 'adb shell device_config set_sync_disabled_for_tests activity_manager/max_phantom_processes 2147483647'

  function handleCopyCommand() {
    bridge.call('copyToClipboard', ppkCommand)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  function handleRequestExclusion() {
    setRequesting(true)
    bridge.call('requestBatteryOptimizationExclusion')
    setTimeout(() => {
      try {
        const status = bridge.callJson<{ isIgnoring: boolean }>('getBatteryOptimizationStatus')
        if (status) setBatteryExcluded(status.isIgnoring)
      } catch { /* ignore */ }
      setRequesting(false)
    }, 3000)
  }

  return (
    <div className="page">
      <div className="page-header">
        <button className="back-btn" onClick={() => navigate('/settings')}>←</button>
        <div className="page-title">{t('ka_title')}</div>
      </div>

      <div style={{ fontSize: 14, color: 'var(--text-secondary)', marginBottom: 20, lineHeight: 1.6 }}>
        {t('ka_desc')}
      </div>

      <div className="section-title">{t('ka_battery')}</div>
      <div className="card">
        <div className="card-row" style={{ cursor: 'default' }}>
          <div className="card-content">
            <div className="card-label">{t('ka_status')}</div>
          </div>
          {batteryExcluded ? (
            <span className="pill pill-success">{t('ka_excluded')}</span>
          ) : (
            <button
              className="btn btn-primary btn-sm"
              onClick={handleRequestExclusion}
              disabled={requesting}
            >
              {requesting ? '...' : t('ka_request')}
            </button>
          )}
        </div>
      </div>

      <div className="section-title">{t('ka_developer')}</div>
      <div className="card">
        <div style={{ fontSize: 14, lineHeight: 1.7, marginBottom: 14, color: 'var(--text-secondary)' }}>
          {t('ka_developer_desc').split('\n').map((line, i, arr) => (
            <span key={i}>{line}{i < arr.length - 1 && <br />}</span>
          ))}
        </div>
        <button
          className="btn btn-secondary btn-sm"
          onClick={() => bridge.call('openSystemSettings', 'developer')}
        >
          {t('ka_open_dev')}
        </button>
      </div>

      <div className="section-title">{t('ka_phantom')}</div>
      <div className="card">
        <div style={{ fontSize: 14, lineHeight: 1.6, marginBottom: 12, color: 'var(--text-secondary)' }}>
          {t('ka_phantom_desc')}
        </div>
        <div className="code-block">
          {ppkCommand}
          <button className="copy-btn" onClick={handleCopyCommand}>
            {copied ? t('ka_copied') : t('ka_copy')}
          </button>
        </div>
      </div>

      <div className="section-title">{t('ka_charge')}</div>
      <div className="card">
        <div style={{ fontSize: 14, color: 'var(--text-secondary)', lineHeight: 1.6 }}>
          {t('ka_charge_desc')}
        </div>
      </div>
    </div>
  )
}
