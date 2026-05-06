import { memo, useState, useEffect, useCallback } from 'react'
import { useRoute } from '../lib/router'
import { bridge } from '../lib/bridge'
import { useNativeEvent } from '../lib/useNativeEvent'
import { t } from '../i18n'

interface Tool { id: string; name: string; desc: string; category: string; size?: string }

function getTools(): Tool[] {
  return [
    { id: 'tmux', name: 'tmux', desc: t('tool_tmux'), category: 'terminal', size: '~2MB' },
    { id: 'code-server', name: 'code-server', desc: t('tool_code_server'), category: 'terminal', size: '~350MB' },
    { id: 'claude-code', name: 'Claude Code', desc: t('tool_claude_code'), category: 'ai', size: '~50MB' },
    { id: 'gemini-cli', name: 'Gemini CLI', desc: t('tool_gemini_cli'), category: 'ai', size: '~30MB' },
    { id: 'codex-cli', name: 'Codex CLI', desc: t('tool_codex_cli'), category: 'ai', size: '~25MB' },
    { id: 'openssh-server', name: 'SSH Server', desc: t('tool_ssh_server'), category: 'network', size: '~5MB' },
    { id: 'ttyd', name: 'ttyd', desc: t('tool_ttyd'), category: 'network', size: '~3MB' },
    { id: 'dufs', name: 'dufs', desc: t('tool_dufs'), category: 'network', size: '~8MB' },
    { id: 'android-tools', name: 'Android Tools', desc: 'ADB — Phantom Process Killer', category: 'system', size: '~15MB' },
    { id: 'chromium', name: 'Chromium', desc: 'Browser automation', category: 'system', size: '~400MB' },
  ]
}

function getCatLabel(cat: string): string {
  const map: Record<string, string> = {
    terminal: `🖥 ${t('tools_cat_terminal')}`,
    ai: `🤖 ${t('tools_cat_ai')}`,
    network: `🌐 ${t('tools_cat_network')}`,
    system: `⚙ ${t('tools_cat_system')}`,
  }
  return map[cat] || cat
}

// ── Confirmation dialog ───────────────────────────────────────────────────

interface ConfirmDialogProps {
  toolName: string
  onConfirm: () => void
  onCancel: () => void
}

const ConfirmDialog = memo(function ConfirmDialog({ toolName, onConfirm, onCancel }: ConfirmDialogProps) {
  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="confirm-title"
      style={{
        position: 'fixed', inset: 0, zIndex: 1000,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        background: 'rgba(0,0,0,0.6)',
        padding: 24,
      }}
    >
      <div style={{
        background: 'var(--bg-secondary)',
        border: '1px solid var(--border)',
        borderRadius: 12,
        padding: 24,
        maxWidth: 320,
        width: '100%',
      }}>
        <div id="confirm-title" style={{ fontWeight: 700, fontSize: 16, marginBottom: 8 }}>
          {t('tools_confirm_uninstall', { name: toolName })}
        </div>
        <div style={{ fontSize: 13, color: 'var(--text-secondary)', marginBottom: 20 }}>
          This will remove the tool from your environment. You can reinstall it later.
        </div>
        <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
          <button className="btn btn-ghost btn-sm" onClick={onCancel}>
            Cancel
          </button>
          <button className="btn btn-danger btn-sm" onClick={onConfirm} autoFocus>
            {t('tools_uninstall')}
          </button>
        </div>
      </div>
    </div>
  )
})

// ── Tool card ─────────────────────────────────────────────────────────────

interface ToolCardProps {
  tool: Tool
  isInstalled: boolean
  isInstalling: boolean
  isUninstalling: boolean
  installDisabled: boolean
  onInstall: () => void
  onUninstall: () => void
}

const ToolCard = memo(function ToolCard({
  tool, isInstalled, isInstalling, isUninstalling, installDisabled, onInstall, onUninstall,
}: ToolCardProps) {
  return (
    <div className="card">
      <div className="card-row">
        <div className="card-content">
          <div className="card-label" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            {tool.name}
            {isInstalled && <span className="pill pill-success" style={{ fontSize: 10 }}>✓</span>}
          </div>
          <div className="card-desc">
            {tool.desc}
            {tool.size && (
              <span style={{ marginLeft: 6, color: 'var(--text-muted)' }}>{tool.size}</span>
            )}
          </div>
        </div>
        <div style={{ display: 'flex', gap: 6, flexShrink: 0 }}>
          {isInstalled ? (
            <button
              className="btn btn-danger btn-sm"
              onClick={onUninstall}
              disabled={installDisabled || isUninstalling}
              aria-label={`Uninstall ${tool.name}`}
            >
              {isUninstalling ? '...' : t('tools_uninstall')}
            </button>
          ) : (
            <button
              className="btn btn-primary btn-sm"
              onClick={onInstall}
              disabled={installDisabled}
              aria-label={`Install ${tool.name}`}
            >
              {isInstalling
                ? <><span className="spinner" style={{ width: 12, height: 12, marginRight: 4 }} />...</>
                : t('tools_install')
              }
            </button>
          )}
        </div>
      </div>
    </div>
  )
})

// ── Main screen ───────────────────────────────────────────────────────────

export function SettingsTools() {
  const { navigate } = useRoute()
  const [installed, setInstalled] = useState<Set<string>>(new Set())
  const [installing, setInstalling] = useState<string | null>(null)
  const [uninstalling, setUninstalling] = useState<string | null>(null)
  const [progress, setProgress] = useState(0)
  const [progressMsg, setProgressMsg] = useState('')
  // Confirmation dialog state
  const [pendingUninstall, setPendingUninstall] = useState<Tool | null>(null)

  useEffect(() => {
    const result = bridge.callJson<Array<{ id: string }>>('getInstalledTools')
    if (result) setInstalled(new Set(result.map(r => r.id)))
  }, [])

  const onInstallProgress = useCallback((data: unknown) => {
    const d = data as { target?: string; progress?: number; message?: string; operation?: string }
    if (d.progress !== undefined) setProgress(d.progress)
    if (d.message) setProgressMsg(d.message)

    if (d.progress !== undefined && d.progress >= 1) {
      // Installation or uninstallation complete
      const isUninstall = d.operation === 'uninstall'
      if (isUninstall && d.target) {
        // Uninstall success: remove from installed set
        setInstalled(prev => { const n = new Set(prev); n.delete(d.target!); return n })
        setUninstalling(null)
      } else if (d.target) {
        // Install success: add to installed set
        setInstalled(prev => new Set([...prev, d.target!]))
        setInstalling(null)
      }
      setProgress(0)
      setProgressMsg('')
    } else if (d.progress !== undefined && d.progress < 0) {
      // Error case
      setInstalling(null)
      setUninstalling(null)
      setProgress(0)
      setProgressMsg('')
    }
  }, [])
  useNativeEvent('install_progress', onInstallProgress)

  const handleInstall = useCallback((id: string) => {
    setInstalling(id)
    setProgress(0)
    setProgressMsg(t('tools_installing', { name: id }))
    bridge.call('installTool', id)
  }, [])

  // Step 1: show confirmation dialog
  const handleUninstallRequest = useCallback((id: string) => {
    const tool = getTools().find(t => t.id === id)
    if (tool) setPendingUninstall(tool)
  }, [])

  // Step 2: confirmed — proceed with uninstall
  // Progress will be tracked via useNativeEvent listener; UI updates when progress >= 1
  const handleUninstallConfirm = useCallback(() => {
    if (!pendingUninstall) return
    const id = pendingUninstall.id
    setPendingUninstall(null)
    setUninstalling(id)
    bridge.call('uninstallTool', id)
    // Do not assume success; wait for install_progress event with progress >= 1
  }, [pendingUninstall])

  const handleUninstallCancel = useCallback(() => {
    setPendingUninstall(null)
  }, [])

  const tools = getTools()
  const categories = [...new Set(tools.map(tool => tool.category))]
  const installedCount = installed.size

  return (
    <div className="page">
      {/* Confirmation dialog */}
      {pendingUninstall && (
        <ConfirmDialog
          toolName={pendingUninstall.name}
          onConfirm={handleUninstallConfirm}
          onCancel={handleUninstallCancel}
        />
      )}

      <div className="page-header">
        <button className="back-btn" onClick={() => navigate('/settings')} aria-label="Back">←</button>
        <div className="page-title">{t('tools_title')}</div>
        {installedCount > 0 && (
          <span className="pill pill-success" style={{ marginLeft: 'auto' }}>
            {installedCount} {installedCount !== 1 ? t('tools_installed').replace('✓', '').trim() + 's' : t('tools_installed').replace('✓', '').trim()}
          </span>
        )}
      </div>

      {/* Install progress */}
      {installing && (
        <div className="card" style={{ marginBottom: 16 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10 }}>
            <div className="spinner" />
            <div style={{ fontSize: 14, fontWeight: 600 }}>
              {t('tools_installing', { name: installing })}
            </div>
          </div>
          <div className="progress-bar">
            <div className="progress-fill" style={{ width: `${Math.round(progress * 100)}%` }} />
          </div>
          {progressMsg && (
            <div style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 6, fontFamily: 'monospace' }}>
              {progressMsg}
            </div>
          )}
        </div>
      )}

      {/* Tools grouped by category */}
      {categories.map(cat => (
        <div key={cat}>
          <div className="section-title">{getCatLabel(cat)}</div>
          {tools.filter(tool => tool.category === cat).map(tool => (
            <ToolCard
              key={tool.id}
              tool={tool}
              isInstalled={installed.has(tool.id)}
              isInstalling={installing === tool.id}
              isUninstalling={uninstalling === tool.id}
              installDisabled={installing !== null}
              onInstall={() => handleInstall(tool.id)}
              onUninstall={() => handleUninstallRequest(tool.id)}
            />
          ))}
        </div>
      ))}
    </div>
  )
}
