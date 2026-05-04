import { useState, useEffect, useCallback } from 'react'
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

export function SettingsTools() {
  const { navigate } = useRoute()
  const [installed, setInstalled] = useState<Set<string>>(new Set())
  const [installing, setInstalling] = useState<string | null>(null)
  const [uninstalling, setUninstalling] = useState<string | null>(null)
  const [progress, setProgress] = useState(0)
  const [progressMsg, setProgressMsg] = useState('')

  useEffect(() => {
    const result = bridge.callJson<Array<{ id: string }>>('getInstalledTools')
    if (result) setInstalled(new Set(result.map(r => r.id)))
  }, [])

  const onInstallProgress = useCallback((data: unknown) => {
    const d = data as { target?: string; progress?: number; message?: string }
    if (d.progress !== undefined) setProgress(d.progress)
    if (d.message) setProgressMsg(d.message)
    if (d.progress !== undefined && d.progress >= 1) {
      if (d.target) setInstalled(prev => new Set([...prev, d.target!]))
      setInstalling(null)
      setProgress(0)
      setProgressMsg('')
    }
  }, [])
  useNativeEvent('install_progress', onInstallProgress)

  function handleInstall(id: string) {
    setInstalling(id)
    setProgress(0)
    setProgressMsg(t('tools_installing', { name: id }))
    bridge.call('installTool', id)
  }

  function handleUninstall(id: string) {
    setUninstalling(id)
    bridge.call('uninstallTool', id)
    setTimeout(() => {
      setInstalled(prev => { const n = new Set(prev); n.delete(id); return n })
      setUninstalling(null)
    }, 1000)
  }

  const tools = getTools()
  const categories = [...new Set(tools.map(tool => tool.category))]
  const installedCount = installed.size

  return (
    <div className="page">
      <div className="page-header">
        <button className="back-btn" onClick={() => navigate('/settings')}>←</button>
        <div className="page-title">{t('tools_title')}</div>
        {installedCount > 0 && (
          <span className="pill pill-success" style={{ marginLeft: 'auto' }}>
            {installedCount} instalada{installedCount !== 1 ? 's' : ''}
          </span>
        )}
      </div>

      {/* Progreso de instalación */}
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

      {/* Herramientas agrupadas por categoría */}
      {categories.map(cat => (
        <div key={cat}>
          <div className="section-title">{getCatLabel(cat)}</div>
          {tools.filter(tool => tool.category === cat).map(tool => {
            const isInstalled = installed.has(tool.id)
            const isInstalling = installing === tool.id
            const isUninstalling = uninstalling === tool.id

            return (
              <div key={tool.id} className="card">
                <div className="card-row">
                  <div className="card-content">
                    <div className="card-label" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                      {tool.name}
                      {isInstalled && (
                        <span className="pill pill-success" style={{ fontSize: 10 }}>✓</span>
                      )}
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
                        onClick={() => handleUninstall(tool.id)}
                        disabled={installing !== null || isUninstalling}
                      >
                        {isUninstalling ? '...' : t('tools_uninstall')}
                      </button>
                    ) : (
                      <button
                        className="btn btn-primary btn-sm"
                        onClick={() => handleInstall(tool.id)}
                        disabled={installing !== null}
                      >
                        {isInstalling ? (
                          <><span className="spinner" style={{ width: 12, height: 12, marginRight: 4 }} />...</>
                        ) : t('tools_install')}
                      </button>
                    )}
                  </div>
                </div>
              </div>
            )
          })}
        </div>
      ))}
    </div>
  )
}
