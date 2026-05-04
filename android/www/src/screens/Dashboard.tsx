import { useState, useEffect, useCallback, useRef } from 'react'
import { bridge } from '../lib/bridge'
import { useNativeEvent } from '../lib/useNativeEvent'
import { t } from '../i18n'

interface BootstrapStatus {
  installed: boolean
  openclawInstalled: boolean
  prefixPath?: string
  source?: string
}
interface PlatformInfo { id: string; name: string }
interface SessionInfo { id: string; active: boolean }
interface InstalledTool { id: string; name: string; version?: string }
interface EnvComponent { version?: string; detected: boolean; path?: string }
interface EnvInfo {
  node?: EnvComponent
  git?: EnvComponent
  openclaw?: EnvComponent
  prefix?: string
  home?: string
}

function RuntimeItem({
  icon, label, version, active, onClick,
}: {
  icon: string
  label: string
  version: string
  active: boolean
  onClick?: () => void
}) {
  return (
    <div
      className={`dash-env-item ${active ? 'active' : 'not-found'} ${onClick ? 'clickable' : ''}`}
      onClick={onClick}
    >
      <div className="dash-env-icon">{icon}</div>
      <div className="dash-env-label">{label}</div>
      <div className="dash-env-status">{version}</div>
      <div className={`dash-env-dot ${active ? 'ok' : 'err'}`} />
    </div>
  )
}

function getCommands() {
  return [
    { icon: '▶', label: 'Gateway', cmd: 'openclaw gateway --host 0.0.0.0', desc: t('cmd_gateway'), color: 'var(--accent)' },
    { icon: '◉', label: 'Status', cmd: 'openclaw status', desc: t('cmd_status'), color: 'var(--success)' },
    { icon: '✦', label: 'Onboard', cmd: 'openclaw onboard', desc: t('cmd_onboard'), color: 'var(--warning)' },
    { icon: '≡', label: 'Logs', cmd: 'openclaw logs --follow', desc: t('cmd_logs'), color: 'var(--text-secondary)' },
  ]
}

function getManagement() {
  return [
    { icon: '↑', label: 'Update', cmd: 'openclaw update', desc: t('cmd_update') },
    { icon: '+', label: 'Install Tools', cmd: 'pkg install git', desc: 'Install git via pkg' },
  ]
}

export function Dashboard() {
  const [bootstrapStatus, setBootstrapStatus] = useState<BootstrapStatus | null>(null)
  const [envInfo, setEnvInfo] = useState<EnvInfo>({})
  const [platform, setPlatform] = useState<PlatformInfo | null>(null)
  const [installedTools, setInstalledTools] = useState<InstalledTool[]>([])
  const [loading, setLoading] = useState(true)
  const [activeSessionId, setActiveSessionId] = useState<string>('')
  const [refreshing, setRefreshing] = useState(false)
  const bridgeReadyRef = useRef(false)
  const retryTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const refreshStatus = useCallback((showSpinner = false) => {
    if (!bridge.isAvailable()) return false

    bridgeReadyRef.current = true
    if (showSpinner) setRefreshing(true)

    const bs = bridge.callJson<BootstrapStatus>('getBootstrapStatus')
    if (bs) setBootstrapStatus(bs)

    // Fetch real versions from getEnvironmentInfo
    const env = bridge.callJson<EnvInfo>('getEnvironmentInfo')
    if (env) setEnvInfo(env)

    const ap = bridge.callJson<PlatformInfo>('getActivePlatform')
    if (ap) setPlatform(ap)

    const sessions = bridge.callJson<SessionInfo[]>('getTerminalSessions')
    if (sessions) {
      const active = sessions.find(s => s.active)
      if (active) setActiveSessionId(active.id)
    }

    const tools = bridge.callJson<InstalledTool[]>('getInstalledTools')
    if (tools) setInstalledTools(tools)

    setLoading(false)
    if (showSpinner) setTimeout(() => setRefreshing(false), 600)
    return true
  }, [])

  useEffect(() => {
    let attempts = 0
    const MAX_ATTEMPTS = 20
    const tryLoad = () => {
      attempts++
      const ok = refreshStatus()
      if (!ok && attempts < MAX_ATTEMPTS) {
        retryTimerRef.current = setTimeout(tryLoad, 200)
      } else if (!ok) {
        setLoading(false)
      }
    }
    tryLoad()
    return () => { if (retryTimerRef.current) clearTimeout(retryTimerRef.current) }
  }, [refreshStatus])

  const onSessionChanged = useCallback((data: unknown) => {
    const d = data as { id?: string; action?: string }
    if ((d.action === 'created' || d.action === 'switched') && d.id) setActiveSessionId(d.id)
  }, [])
  useNativeEvent('session_changed', onSessionChanged)

  const onSetupProgress = useCallback((data: unknown) => {
    const d = data as { progress?: number }
    if (d.progress === 1) setTimeout(() => refreshStatus(), 500)
  }, [refreshStatus])
  useNativeEvent('setup_progress', onSetupProgress)

  function runInTerminal(cmd: string) {
    bridge.call('showTerminal')
    setTimeout(() => bridge.call('writeToTerminal', activeSessionId, cmd + '\n'), 150)
  }

  function installGit() {
    bridge.call('createSession')
    bridge.call('showTerminal')
    setTimeout(() => bridge.call('writeToTerminal', activeSessionId, 'pkg install -y git\n'), 300)
  }

  if (loading) {
    return (
      <div className="page">
        <div className="empty-state" style={{ minHeight: 'calc(100dvh - 80px)' }}>
          <div className="spinner" style={{ width: 36, height: 36, borderWidth: 3 }} />
        </div>
      </div>
    )
  }

  if (!bridge.isAvailable() && !bridgeReadyRef.current) {
    return (
      <div className="page">
        <div className="empty-state" style={{ minHeight: 'calc(100dvh - 80px)' }}>
          <div style={{ fontSize: 32, marginBottom: 8 }}>🔌</div>
          <div style={{ fontSize: 16, fontWeight: 600 }}>{t('about_bridge_unavailable')}</div>
          <div className="empty-state-text">{t('about_running_outside')}</div>
        </div>
      </div>
    )
  }

  const isInstalled = bootstrapStatus?.installed && bootstrapStatus?.openclawInstalled

  // Resolve display versions from envInfo (real versions) or fallback to bootstrapStatus
  const nodeVersion = envInfo.node?.detected
    ? (envInfo.node.version || t('env_detected'))
    : (bootstrapStatus?.installed ? t('env_detected') : t('env_not_detected'))
  const nodeActive = !!(envInfo.node?.detected || bootstrapStatus?.installed)

  const gitVersion = envInfo.git?.detected
    ? (envInfo.git.version || t('env_detected'))
    : t('env_not_detected')
  const gitActive = !!(envInfo.git?.detected)

  const ocVersion = envInfo.openclaw?.detected
    ? (envInfo.openclaw.version || t('env_detected'))
    : (bootstrapStatus?.openclawInstalled ? t('env_detected') : t('env_not_detected'))
  const ocActive = !!(envInfo.openclaw?.detected || bootstrapStatus?.openclawInstalled)

  return (
    <div className="page">
      {!isInstalled && (
        <div className="card" style={{
          background: 'var(--bg-tertiary)',
          border: '1px solid var(--warning)',
          marginBottom: 16,
          display: 'flex', flexDirection: 'column', gap: 12,
        }}>
          <div style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
            <span style={{ fontSize: 24 }}>⚠️</span>
            <div>
              <div style={{ fontWeight: 700, color: 'var(--warning)' }}>{t('dash_setup_required')}</div>
              <div style={{ fontSize: 13, color: 'var(--text-secondary)' }}>{t('dash_setup_desc')}</div>
            </div>
          </div>
          <button
            className="btn btn-primary"
            style={{ width: '100%', padding: '12px', fontSize: '15px', fontWeight: 600 }}
            onClick={() => window.location.hash = '/setup'}
          >
            🚀 {t('setup_start')}
          </button>
        </div>
      )}

      {/* Header */}
      <div className="dash-header">
        <div className="dash-platform-icon">
          <img src="./openclaw.svg" alt="OpenClaw" style={{ width: 28, height: 28 }} />
        </div>
        <div className="dash-platform-info">
          <div className="dash-platform-name">{platform?.name || 'OpenClaw'}</div>
          <div className="dash-platform-status">
            <span className={`status-dot ${isInstalled ? 'success' : 'warning'}`} />
            <span>{isInstalled ? t('platforms_active') : t('dash_setup_required')}</span>
          </div>
        </div>
        <button
          className={`dash-refresh-btn${refreshing ? ' spinning' : ''}`}
          onClick={() => refreshStatus(true)}
          aria-label="Refresh"
        >↻</button>
      </div>

      {/* Runtime — shows real versions */}
      <div className="section-title">{t('dash_runtime')}</div>
      <div className="runtime-grid">
        <RuntimeItem
          icon="⬢"
          label="Node.js"
          version={nodeVersion}
          active={nodeActive}
          onClick={!nodeActive ? () => (window.location.hash = '/setup') : undefined}
        />
        <RuntimeItem
          icon="⎇"
          label="git"
          version={gitVersion}
          active={gitActive}
          onClick={!gitActive ? installGit : undefined}
        />
        <RuntimeItem
          icon="🦀"
          label="openclaw"
          version={ocVersion}
          active={ocActive}
          onClick={!ocActive ? () => (window.location.hash = '/setup') : undefined}
        />
      </div>

      {/* Git install hint when not available */}
      {!gitActive && isInstalled && (
        <div className="card" style={{
          background: 'var(--warning-dim)',
          border: '1px solid var(--warning)',
          marginBottom: 4,
          padding: '12px 16px',
        }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12 }}>
            <div>
              <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--warning)' }}>git no disponible</div>
              <div style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 2 }}>
                Necesario para clonar repositorios
              </div>
            </div>
            <button
              className="btn btn-sm"
              style={{ background: 'var(--warning)', color: '#000', fontWeight: 700, flexShrink: 0 }}
              onClick={installGit}
            >
              Instalar git
            </button>
          </div>
        </div>
      )}

      {/* Installed tools */}
      {installedTools.length > 0 && (
        <>
          <div className="section-title">{t('tools_title')}</div>
          <div className="card">
            <div className="dash-tools-row">
              {installedTools.map(tool => (
                <span key={tool.id} className="pill pill-success dash-tool-pill">
                  ✓ {tool.name}
                </span>
              ))}
            </div>
          </div>
        </>
      )}

      {/* Commands */}
      <div className="section-title">{t('dash_commands')}</div>
      <div className="card" style={{ padding: 0, overflow: 'hidden', opacity: isInstalled ? 1 : 0.5 }}>
        {getCommands().map((item, i) => (
          <CommandRow
            key={item.cmd}
            icon={item.icon}
            label={item.label}
            cmd={item.cmd}
            color={item.color}
            borderTop={i > 0}
            onClick={() => isInstalled ? runInTerminal(item.cmd) : alert(t('dash_setup_required'))}
          />
        ))}
      </div>

      {/* Management */}
      <div className="section-title">{t('dash_management')}</div>
      <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
        {getManagement().map((item, i) => (
          <CommandRow
            key={item.cmd}
            icon={item.icon}
            label={item.label}
            cmd={item.cmd}
            color="var(--success)"
            borderTop={i > 0}
            onClick={() => runInTerminal(item.cmd)}
          />
        ))}
      </div>

      {/* Quick actions */}
      <div className="section-title">{t('dash_quick_actions')}</div>
      <div className="dash-quick-grid">
        <QuickAction icon="🖥" label={t('dash_sessions')} onClick={() => {
          const sessions = bridge.callJson<SessionInfo[]>('getTerminalSessions')
          if (sessions && sessions.length > 0) bridge.call('showTerminal')
          else { bridge.call('createSession'); bridge.call('showTerminal') }
        }} />
        <QuickAction icon="➕" label={t('dash_new_session')} onClick={() => {
          bridge.call('createSession')
          bridge.call('showTerminal')
        }} />
        <QuickAction icon="🔄" label={t('dash_reload_ui')} onClick={() => window.location.reload()} />
        <QuickAction icon="⚙" label={t('settings_title')} onClick={() => {
          bridge.call('showWebView')
          window.location.hash = '/settings'
        }} />
      </div>
    </div>
  )
}

function QuickAction({ icon, label, onClick }: { icon: string; label: string; onClick: () => void }) {
  const [pressed, setPressed] = useState(false)
  return (
    <button
      className={`dash-quick-btn${pressed ? ' pressed' : ''}`}
      onClick={onClick}
      onTouchStart={() => setPressed(true)}
      onTouchEnd={() => setPressed(false)}
      onMouseDown={() => setPressed(true)}
      onMouseUp={() => setPressed(false)}
      onMouseLeave={() => setPressed(false)}
    >
      <span className="dash-quick-icon">{icon}</span>
      <span className="dash-quick-label">{label}</span>
    </button>
  )
}

function CommandRow({
  icon, label, cmd, color, borderTop, onClick,
}: {
  icon: string; label: string; cmd: string; color: string
  borderTop: boolean; onClick: () => void
}) {
  const [pressed, setPressed] = useState(false)
  return (
    <div
      role="button"
      tabIndex={0}
      onClick={onClick}
      onTouchStart={() => setPressed(true)}
      onTouchEnd={(e) => { e.preventDefault(); setPressed(false); onClick() }}
      onTouchCancel={() => setPressed(false)}
      onMouseDown={() => setPressed(true)}
      onMouseUp={() => setPressed(false)}
      onMouseLeave={() => setPressed(false)}
      onKeyDown={e => e.key === 'Enter' && onClick()}
      style={{
        display: 'flex', alignItems: 'center', gap: 14,
        padding: '13px 16px',
        borderTop: borderTop ? '1px solid var(--border-subtle)' : 'none',
        cursor: 'pointer',
        background: pressed ? 'var(--bg-tertiary)' : 'transparent',
        transition: 'background 0.1s',
        userSelect: 'none',
        WebkitUserSelect: 'none',
      }}
    >
      <div style={{
        width: 36, height: 36, borderRadius: 9,
        background: 'var(--bg-tertiary)',
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        fontSize: 15, color, fontWeight: 700, flexShrink: 0,
      }}>
        {icon}
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 14, fontWeight: 600 }}>{label}</div>
        <div style={{
          fontSize: 11, color: 'var(--text-secondary)',
          fontFamily: 'monospace', marginTop: 2,
          overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
        }}>
          {cmd}
        </div>
      </div>
      <span style={{ color: 'var(--text-muted)', fontSize: 18, flexShrink: 0 }}>›</span>
    </div>
  )
}
