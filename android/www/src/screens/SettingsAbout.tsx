import { useState, useEffect } from 'react'
import { useRoute } from '../lib/router'
import { bridge } from '../lib/bridge'

interface AppInfo {
  versionName: string
  versionCode: number
  packageName: string
}

export function SettingsAbout() {
  const { navigate } = useRoute()
  const [appInfo, setAppInfo] = useState<AppInfo | null>(null)
  const [runtimeInfo, setRuntimeInfo] = useState<Record<string, string>>({})  
  const [scriptVersion, setScriptVersion] = useState<string>('—')

  useEffect(() => {
    const info = bridge.callJson<AppInfo>('getAppInfo')
    if (info) setAppInfo(info)

    // Get script version (oa CLI)
    const oaV = bridge.callJson<{ stdout: string }>('runCommand', 'oa --version 2>/dev/null')
    setScriptVersion(oaV?.stdout?.trim()?.replace(/^oa\s+/, '') || '—')

    // Get runtime versions
    const nodeV = bridge.callJson<{ stdout: string }>('runCommand', 'node -v 2>/dev/null')
    const gitV = bridge.callJson<{ stdout: string }>('runCommand', 'git --version 2>/dev/null')
    setRuntimeInfo({
      'Node.js': nodeV?.stdout?.trim() || '—',
      'git': gitV?.stdout?.trim()?.replace('git version ', '') || '—',
    })
  }, [])

  return (
    <div className="page">
      <div className="page-header">
        <button className="back-btn" onClick={() => navigate('/settings')}>←</button>
        <div className="page-title">About</div>
      </div>

      <div style={{ textAlign: 'center', padding: '24px 0' }}>
        <div style={{ fontSize: 48, marginBottom: 8 }}>🧠</div>
        <div style={{ fontSize: 20, fontWeight: 700 }}>Claw</div>
      </div>

      <div className="section-title">Version</div>
      <div className="card">
        <div className="info-row">
          <span className="label">APK</span>
          <span>{appInfo?.versionName || '—'}</span>
        </div>
        <div className="info-row">
          <span className="label">Script (oa)</span>
          <span>{scriptVersion}</span>
        </div>
        <div className="info-row">
          <span className="label">Package</span>
          <span style={{ fontSize: 12 }}>{appInfo?.packageName || '—'}</span>
        </div>
      </div>

      <div className="section-title">Runtime</div>
      <div className="card">
        {Object.entries(runtimeInfo).map(([key, val]) => (
          <div className="info-row" key={key}>
            <span className="label">{key}</span>
            <span>{val}</span>
          </div>
        ))}
      </div>

      <div className="divider" />

      <div className="card">
        <div className="info-row">
          <span className="label">License</span>
          <span>GPL v3</span>
        </div>
      </div>

      <div style={{ display: 'flex', gap: 12, marginTop: 16 }}>
        <button
          className="btn btn-secondary"
          style={{ flex: 1 }}
          onClick={() => {
            bridge.call('openSystemSettings', 'app_info')
          }}
        >
          App Info
        </button>
      </div>

      <div style={{
        textAlign: 'center',
        color: 'var(--text-secondary)',
        fontSize: 13,
        marginTop: 32,
      }}>
        Made for Android
      </div>
    </div>
  )
}
