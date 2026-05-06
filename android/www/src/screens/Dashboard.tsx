import { memo, useState, useCallback } from 'react'
import { bridge } from '../lib/bridge'
import { useNativeEvent } from '../lib/useNativeEvent'
import { useAppContext } from '../contexts/AppContext'
import { t } from '../i18n'

// ── Memoized sub-components ───────────────────────────────────────────────

const RuntimeItem = memo(function RuntimeItem({
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
      role={onClick ? 'button' : undefined}
      tabIndex={onClick ? 0 : undefined}
      onKeyDown={onClick ? (e) => e.key === 'Enter' && onClick() : undefined}
    >
      <div className="dash-env-icon">{icon}</div>
      <div className="dash-env-label">{label}</div>
      <div className="dash-env-status">{version}</div>
      <div className={`dash-env-dot ${active ? 'ok' : 'err'}`} />
    </div>
  )
})

const CommandRow = memo(function CommandRow({
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
})

const QuickAction = memo(function QuickAction({
  icon, label, onClick,
}: { icon: string; label: string; onClick: () => void }) {
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
})

// ── Static data helpers ───────────────────────────────────────────────────

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

// ── Skeleton loader ───────────────────────────────────────────────────────

function DashboardSkeleton() {
  return (
    <div className="page">
      <div className="dash-header">
        <div className="dash-platform-icon skeleton" style={{ background: 'var(--bg-tertiary)' }} />
        <div className="dash-platform-info" style={{ flex: 1 }}>
          <div className="skeleton" style={{ height: 20, width: '60%', marginBottom: 6, background: 'var(--bg-tertiary)' }} />
          <div className="skeleton" style={{ height: 12, width: '40%', background: 'var(--bg-tertiary)' }} />
        </div>
        <div className="dash-refresh-btn skeleton" style={{ background: 'var(--bg-tertiary)' }} />
      </div>
      <div className="section-title skeleton" style={{ height: 11, width: '30%', background: 'var(--bg-tertiary)' }} />
      <div className="runtime-grid" style={{ gridTemplateColumns: 'repeat(4, 1fr)', marginBottom: 16 }}>
        {[1, 2, 3, 4].map(i => (
          <div key={i} className="dash-env-item">
            <div className="skeleton" style={{ width: 20, height: 20, borderRadius: '50%', background: 'var(--bg-tertiary)' }} />
            <div className="skeleton" style={{ width: '60%', height: 8, marginTop: 4, background: 'var(--bg-tertiary)' }} />
            <div className="skeleton" style={{ width: '80%', height: 8, marginTop: 2, background: 'var(--bg-tertiary)' }} />
          </div>
        ))}
      </div>
      <div className="section-title skeleton" style={{ height: 11, width: '40%', background: 'var(--bg-tertiary)' }} />
      <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
        {[1, 2, 3, 4].map(i => (
          <div key={i} style={{
            display: 'flex', alignItems: 'center', gap: 14,
            padding: '13px 16px',
            borderTop: i > 1 ? '1px solid var(--border-subtle)' : 'none',
          }}>
            <div className="skeleton" style={{ width: 36, height: 36, borderRadius: 9, background: 'var(--bg-tertiary)' }} />
            <div style={{ flex: 1 }}>
              <div className="skeleton" style={{ height: 14, width: '40%', marginBottom: 4, background: 'var(--bg-tertiary)' }} />
              <div className="skeleton" style={{ height: 10, width: '70%', background: 'var(--bg-tertiary)' }} />
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}

// ── Main component ────────────────────────────────────────────────────────

export function Dashboard() {
  const {
    loading,
    setupStatus,
    envInfo,
    sessions,
    activeSessionId,
    installedTools,
    refresh,
  } = useAppContext()

  const [refreshing, setRefreshing] = useState(false)

  // Listen to session changes to keep activeSessionId in sync
  const onSessionChanged = useCallback((data: unknown) => {
    const d = data as { id?: string; action?: string }
    if ((d.action === 'created' || d.action === 'switched') && d.id) {
      refresh()
    }
  }, [refresh])
  useNativeEvent('session_changed', onSessionChanged)

  const handleRefresh = useCallback(async () => {
    setRefreshing(true)
    await refresh(true)
    setTimeout(() => setRefreshing(false), 600)
  }, [refresh])

  if (loading) return <DashboardSkeleton />

  if (!bridge.isAvailable()) {
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

  const isInstalled = !!(setupStatus?.bootstrapInstalled && setupStatus?.openclawReady)
  const currentSessionId = activeSessionId || sessions.find(s => s.active)?.id || ''

  // Resolve display versions from envInfo
  const nodeVersion = envInfo.node?.detected
    ? (envInfo.node.version || t('env_detected'))
    : (setupStatus?.bootstrapInstalled ? t('env_detected') : t('env_not_detected'))
  const nodeActive = !!(envInfo.node?.detected || setupStatus?.bootstrapInstalled)

  const npmVersion = envInfo.npm?.detected
    ? (envInfo.npm.version || t('env_detected'))
    : t('env_not_detected')
  const npmActive = !!(envInfo.npm?.detected)

  const gitVersion = envInfo.git?.detected
    ? (envInfo.git.version || t('env_detected'))
    : t('env_not_detected')
  const gitActive = !!(envInfo.git?.detected)

  const ocVersion = envInfo.openclaw?.detected
    ? (envInfo.openclaw.version || t('env_detected'))
    : (setupStatus?.openclawReady ? t('env_detected') : t('env_not_detected'))
  const ocActive = !!(envInfo.openclaw?.detected || setupStatus?.openclawReady)

  function runInTerminal(cmd: string) {
    bridge.call('showTerminal')
    setTimeout(() => bridge.call('writeToTerminal', currentSessionId, cmd + '\n'), 150)
  }

  function installGit() {
    bridge.call('showTerminal')
    const activeSessions = bridge.callJson<Array<{ id: string; active: boolean }>>('getTerminalSessions')
    const active = activeSessions?.find(s => s.active)
    const id = active?.id || bridge.callJson<{ id: string }>('createSession')?.id || ''
    if (id) {
      setTimeout(() => {
        bridge.call('writeToTerminal', id,
          'apt-get install -y git 2>/dev/null || pkg install -y git 2>/dev/null || echo "Install git via: openclaw gateway"\n'
        )
      }, 300)
    }
  }

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
            onClick={() => { window.location.hash = '/setup' }}
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
          <div className="dash-platform-name">OpenClaw</div>
          <div className="dash-platform-status">
            <span className={`status-dot ${isInstalled ? 'success' : 'warning'}`} />
            <span>{isInstalled ? t('platforms_active') : t('dash_setup_required')}</span>
          </div>
        </div>
        <button
          className={`dash-refresh-btn${refreshing ? ' spinning' : ''}`}
          onClick={handleRefresh}
          aria-label="Refresh"
        >↻</button>
      </div>

      {/* Runtime grid */}
      <div className="section-title">{t('dash_runtime')}</div>
      <div className="runtime-grid" style={{ gridTemplateColumns: 'repeat(4, 1fr)' }}>
        <RuntimeItem icon="⬢" label="Node.js" version={nodeVersion} active={nodeActive}
          onClick={!nodeActive ? () => { window.location.hash = '/setup' } : undefined} />
        <RuntimeItem icon="📦" label="npm" version={npmVersion} active={npmActive}
          onClick={!npmActive ? () => { window.location.hash = '/setup' } : undefined} />
        <RuntimeItem icon="⎇" label="git" version={gitVersion} active={gitActive}
          onClick={!gitActive ? installGit : undefined} />
        <RuntimeItem icon="🦀" label="openclaw" version={ocVersion} active={ocActive}
          onClick={!ocActive ? () => { window.location.hash = '/setup' } : undefined} />
      </div>

      {/* Git install hint */}
      {!gitActive && isInstalled && (
        <div className="card" style={{
          background: 'var(--warning-dim)',
          border: '1px solid var(--warning)',
          marginBottom: 4,
          padding: '12px 16px',
        }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12 }}>
            <div>
              <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--warning)' }}>{t('git_not_available')}</div>
              <div style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 2 }}>{t('git_install_hint')}</div>
            </div>
            <button
              className="btn btn-sm"
              style={{ background: 'var(--warning)', color: '#000', fontWeight: 700, flexShrink: 0 }}
              onClick={installGit}
            >
              {t('git_install_btn')}
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
          if (sessions.length > 0) bridge.call('showTerminal')
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
