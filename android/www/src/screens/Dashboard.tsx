import { useState, useEffect } from 'react'
import { useRoute } from '../lib/router'
import { bridge } from '../lib/bridge'

interface BootstrapStatus {
  installed: boolean
  prefixPath?: string
}

interface PlatformInfo {
  id: string
  name: string
}

export function Dashboard() {
  const { navigate } = useRoute()
  const [status, setStatus] = useState<BootstrapStatus | null>(null)
  const [platform, setPlatform] = useState<PlatformInfo | null>(null)
  const [runtimeInfo, setRuntimeInfo] = useState<Record<string, string>>({})

  function refreshStatus() {
    const bs = bridge.callJson<BootstrapStatus>('getBootstrapStatus')
    if (bs) setStatus(bs)

    const ap = bridge.callJson<PlatformInfo>('getActivePlatform')
    if (ap) setPlatform(ap)

    // Get runtime versions
    const nodeV = bridge.callJson<{ stdout: string }>('runCommand', 'node -v 2>/dev/null')
    const gitV = bridge.callJson<{ stdout: string }>('runCommand', 'git --version 2>/dev/null')
    const ocV = bridge.callJson<{ stdout: string }>('runCommand', 'openclaw --version 2>/dev/null')
    setRuntimeInfo({
      'Node.js': nodeV?.stdout?.trim() || '—',
      'git': gitV?.stdout?.trim()?.replace('git version ', '') || '—',
      'openclaw': ocV?.stdout?.trim() || '—',
    })
  }

  useEffect(() => {
    refreshStatus()
  }, [])

  function handleCheckStatus() {
    bridge.call('showTerminal')
    bridge.call('writeToTerminal', '', 'openclaw status\n')
  }


  function handleUpdate() {
    bridge.call('showTerminal')
    bridge.call('writeToTerminal', '', 'npm install -g openclaw@latest --ignore-scripts && echo "Update complete. Version: $(openclaw --version)"\n')
  }

  function handleInstallTools() {
    navigate('/settings/tools')
  }

  function handleStartGateway() {
    bridge.call('showTerminal')
    bridge.call('writeToTerminal', '', 'openclaw gateway\n')
  }

  if (!status?.installed) {
    return (
      <div className="page">
        <div className="setup-container" style={{ minHeight: 'calc(100vh - 80px)' }}>
          <div className="setup-logo">🧠</div>
          <div className="setup-title">Setup Required</div>
          <div className="setup-subtitle">
            The runtime environment hasn't been set up yet.
          </div>
        </div>
      </div>
    )
  }

  return (
    <div className="page">
      {/* Platform header */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 24 }}>
        <span style={{ fontSize: 36 }}>🧠</span>
        <div>
          <div style={{ fontSize: 20, fontWeight: 700 }}>
            {platform?.name || 'OpenClaw'}
          </div>
        </div>
      </div>

      {/* Gateway */}
      <div className="card">
        <div style={{ fontSize: 13, color: 'var(--text-secondary)', marginBottom: 12 }}>
          Gateway
        </div>
        <div style={{ display: 'flex', gap: 8 }}>
          <button className="btn btn-primary" style={{ flex: 1 }} onClick={handleStartGateway}>
            Start Gateway
          </button>
          <button className="btn btn-secondary" style={{ flex: 1 }} onClick={handleCheckStatus}>
            Check Status
          </button>
        </div>
      </div>

      {/* Runtime info */}
      <div className="section-title">Runtime</div>
      <div className="card">
        {Object.entries(runtimeInfo).map(([key, val]) => (
          <div className="info-row" key={key}>
            <span className="label">{key}</span>
            <span>{val}</span>
          </div>
        ))}
      </div>

      {/* Management */}
      <div className="section-title">Management</div>
      <div className="card" style={{ cursor: 'pointer' }} onClick={handleUpdate}>
        <div className="card-row">
          <div className="card-icon">⬆️</div>
          <div className="card-content">
            <div className="card-label">Update</div>
            <div className="card-desc">Update OpenClaw to latest version</div>
          </div>
          <div className="card-chevron">›</div>
        </div>
      </div>
      <div className="card" style={{ cursor: 'pointer' }} onClick={handleInstallTools}>
        <div className="card-row">
          <div className="card-icon">🧩</div>
          <div className="card-content">
            <div className="card-label">Install Tools</div>
            <div className="card-desc">Add or remove optional tools</div>
          </div>
          <div className="card-chevron">›</div>
        </div>
      </div>
    </div>
  )
}
