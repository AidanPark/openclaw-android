import { memo, useState, useCallback, useEffect, Fragment } from 'react'
import { bridge } from '../lib/bridge'
import { useNativeEvent } from '../lib/useNativeEvent'
import { t } from '../i18n'

interface Props {
  onComplete: () => void
}

type SetupPhase = 'welcome' | 'mode-select' | 'tool-select' | 'installing' | 'done' | 'failed'

function getOptionalTools() {
  return [
    { id: 'tmux', name: 'tmux', desc: t('tool_tmux'), icon: '🖥' },
    { id: 'code-server', name: 'code-server', desc: t('tool_code_server'), icon: '💻' },
    { id: 'claude-code', name: 'Claude Code', desc: t('tool_claude_code'), icon: '🤖' },
    { id: 'gemini-cli', name: 'Gemini CLI', desc: t('tool_gemini_cli'), icon: '✨' },
    { id: 'codex-cli', name: 'Codex CLI', desc: t('tool_codex_cli'), icon: '🧠' },
    { id: 'ttyd', name: 'ttyd', desc: t('tool_ttyd'), icon: '🌐' },
    { id: 'dufs', name: 'dufs', desc: t('tool_dufs'), icon: '📁' },
  ]
}

function getTips() {
  return [t('tip_1'), t('tip_2'), t('tip_3'), t('tip_4')]
}

// ── Memoized tip card ─────────────────────────────────────────────────────
const TipCard = memo(function TipCard({ tip }: { tip: string }) {
  return <div className="tip-card">💡 {tip}</div>
})

// ── Stepper ───────────────────────────────────────────────────────────────
const Stepper = memo(function Stepper({ currentStep }: { currentStep: number }) {
  const steps = [t('step_platform'), t('step_path'), t('step_tools'), t('step_setup')]
  return (
    <div className="stepper">
      {steps.map((label, i) => (
        <Fragment key={label}>
          {i > 0 && <div className={`step-line${i <= currentStep ? ' done' : ''}`} />}
          <div className={`step${i < currentStep ? ' done' : i === currentStep ? ' active' : ''}`}>
            <span className="step-icon">{i < currentStep ? '✓' : i === currentStep ? '●' : '○'}</span>
            <span className="step-label-text">{label}</span>
          </div>
        </Fragment>
      ))}
    </div>
  )
})

// ── Main component ────────────────────────────────────────────────────────
export function Setup({ onComplete }: Props) {
  const [phase, setPhase] = useState<SetupPhase>('welcome')
  const [selectedTools, setSelectedTools] = useState<Set<string>>(new Set())
  const [progress, setProgress] = useState(0)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [tipIndex, setTipIndex] = useState(0)
  const [checkingConn, setCheckingConn] = useState(false)
  const [connResult, setConnResult] = useState<'ok' | 'fail' | null>(null)
  const [installMode, setInstallMode] = useState<'online' | 'offline'>('online')
  const [hasAsset, setHasAsset] = useState(false)
  const [selectedFileName, setSelectedFileName] = useState<string | null>(null)

  useEffect(() => {
    const d = bridge.callJson<{ hasPayload: boolean }>('hasPayloadAsset')
    if (d) {
      setHasAsset(!!d.hasPayload)
      if (d.hasPayload) setInstallMode('offline')
    }
  }, [])

  const onPayloadSelected = useCallback((data: unknown) => {
    const d = data as { name: string }
    if (d.name) setSelectedFileName(d.name)
  }, [])
  useNativeEvent('payload_file_selected', onPayloadSelected)

  // Rotate tips during installation
  useEffect(() => {
    if (phase !== 'installing') return
    const id = setInterval(() => setTipIndex(i => (i + 1) % getTips().length), 4000)
    return () => clearInterval(id)
  }, [phase])

  const onProgress = useCallback((data: unknown) => {
    const d = data as { progress?: number; message?: string; error?: string }
    if (d.progress !== undefined) setProgress(d.progress)
    if (d.message) setMessage(d.message)
    if (d.error) {
      setError(d.error)
      setPhase('failed')
    }
    if (d.progress !== undefined && d.progress >= 1 && !d.error) {
      setPhase('done')
    }
  }, [])
  useNativeEvent('setup_progress', onProgress)

  const toggleTool = useCallback((id: string) => {
    setSelectedTools(prev => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }, [])

  const handleStartSetup = useCallback(() => {
    const selections: Record<string, boolean> = {}
    getOptionalTools().forEach(tool => {
      selections[tool.id] = selectedTools.has(tool.id)
    })
    bridge.call('saveToolSelections', JSON.stringify(selections))
    bridge.call('saveInstallPath', 'local')

    setPhase('installing')
    setProgress(0)
    setMessage(t('setup_preparing'))
    setError('')
    setConnResult(null)

    bridge.call('startSetup', installMode)
  }, [selectedTools, installMode])

  const handleCheckConnection = useCallback(() => {
    setCheckingConn(true)
    setConnResult(null)

    const handler = (e: Event) => {
      const d = (e as CustomEvent).detail as { callbackId?: string; result?: string }
      if (d?.callbackId === 'conn_test') {
        setCheckingConn(false)
        setConnResult(d.result?.includes('OK') ? 'ok' : 'fail')
        window.removeEventListener('native:command_result', handler)
      }
    }
    window.addEventListener('native:command_result', handler)

    bridge.call(
      'runCommandAsync',
      'conn_test',
      'curl -sfI --connect-timeout 5 https://registry.npmjs.org/ >/dev/null 2>&1 && echo OK || echo FAIL'
    )

    // Timeout after 12 s
    const timeout = setTimeout(() => {
      setCheckingConn(false)
      setConnResult('fail')
      window.removeEventListener('native:command_result', handler)
    }, 12000)

    // Clean up timeout if handler fires first
    window.addEventListener('native:command_result', () => clearTimeout(timeout), { once: true })
  }, [])

  const currentStep = phase === 'welcome' ? 0
    : phase === 'mode-select' ? 1
      : phase === 'tool-select' ? 2
        : 3

  // ── Welcome ───────────────────────────────────────────────────────────
  if (phase === 'welcome') {
    return (
      <div className="setup-container">
        <Stepper currentStep={currentStep} />
        <div className="setup-logo">
          <img src="./openclaw.svg" alt="OpenClaw" style={{ width: 80, height: 80 }} />
        </div>
        <div className="setup-title">OpenClaw</div>
        <div className="setup-subtitle">{t('setup_choose_platform')}</div>
        <div className="setup-feature-list">
          <div className="setup-feature-item"><span className="setup-feature-icon">⚡</span><span>{t('tip_3')}</span></div>
          <div className="setup-feature-item"><span className="setup-feature-icon">🔒</span><span>{t('tip_4')}</span></div>
          <div className="setup-feature-item"><span className="setup-feature-icon">🔄</span><span>{t('tip_2')}</span></div>
        </div>
        <button className="btn btn-primary btn-full" onClick={() => setPhase('mode-select')}>
          {t('setup_next')} →
        </button>
      </div>
    )
  }

  // ── Mode select ───────────────────────────────────────────────────────
  if (phase === 'mode-select') {
    return (
      <div className="setup-container">
        <Stepper currentStep={currentStep} />
        <div className="setup-title">{t('setup_mode_title')}</div>

        <div className="card-group" style={{ width: '100%', maxWidth: 400 }}>
          <div
            className={`card clickable ${installMode === 'online' ? 'selected' : ''}`}
            onClick={() => setInstallMode('online')}
            style={installMode === 'online' ? { borderColor: 'var(--accent)', background: 'var(--accent-dim)' } : {}}
          >
            <div className="card-row">
              <div className="card-icon">🌐</div>
              <div className="card-content">
                <div className="card-label">{t('setup_mode_online_label')}</div>
                <div className="card-desc">{t('setup_mode_online_desc')}</div>
              </div>
            </div>
            {installMode === 'online' && (
              <div style={{ marginTop: 10, padding: '0 4px' }}>
                <div style={{
                  background: 'var(--bg-primary)', border: '1px solid var(--border)',
                  borderRadius: 6, padding: '8px 12px',
                  fontFamily: 'monospace', fontSize: 11, color: 'var(--text-secondary)',
                  wordBreak: 'break-all', lineHeight: 1.6,
                }}>
                  curl -sL myopenclawhub.com/install | bash
                </div>
                <div style={{ fontSize: 11, color: 'var(--text-muted)', marginTop: 6 }}>
                  {t('setup_mode_online_hint')}
                </div>
              </div>
            )}
          </div>

          <div
            className={`card clickable ${installMode === 'offline' ? 'selected' : ''}`}
            onClick={() => setInstallMode('offline')}
            style={installMode === 'offline' ? { borderColor: 'var(--accent)', background: 'var(--accent-dim)' } : {}}
          >
            <div className="card-row">
              <div className="card-icon">📦</div>
              <div className="card-content">
                <div className="card-label">{t('setup_mode_offline_label')}</div>
                <div className="card-desc">{t('setup_mode_offline_desc')}</div>
              </div>
            </div>
            {installMode === 'offline' && (
              <div style={{ marginTop: 12, padding: '0 8px' }}>
                {!hasAsset && !selectedFileName && (
                  <div style={{ fontSize: 12, color: 'var(--warning)', marginBottom: 8 }}>
                    ⚠️ {t('setup_offline_not_found')}
                  </div>
                )}
                {selectedFileName && (
                  <div style={{ fontSize: 12, color: 'var(--success)', marginBottom: 8 }}>
                    ✓ {t('setup_offline_selected', { name: selectedFileName })}
                  </div>
                )}
                <button
                  className="btn btn-secondary btn-sm btn-full"
                  onClick={(e) => { e.stopPropagation(); bridge.call('pickPayloadFile') }}
                >
                  {t('setup_offline_select')}
                </button>
              </div>
            )}
          </div>
        </div>

        <div className="setup-actions">
          <button className="btn btn-ghost btn-sm" onClick={() => setPhase('welcome')}>
            ← {t('step_platform')}
          </button>
          <button
            className="btn btn-primary"
            onClick={() => setPhase('tool-select')}
            disabled={installMode === 'offline' && !hasAsset && !selectedFileName}
          >
            {t('setup_next')} →
          </button>
        </div>
      </div>
    )
  }

  // ── Tool select ───────────────────────────────────────────────────────
  if (phase === 'tool-select') {
    return (
      <div className="setup-container setup-container--scroll">
        <Stepper currentStep={currentStep} />
        <div className="setup-title" style={{ fontSize: 22 }}>{t('setup_optional_tools')}</div>
        <div className="setup-subtitle">{t('setup_tools_desc', { platform: 'OpenClaw' })}</div>

        <div className="setup-tools-grid">
          {getOptionalTools().map(tool => {
            const isSelected = selectedTools.has(tool.id)
            return (
              <div
                key={tool.id}
                className={`setup-tool-card${isSelected ? ' selected' : ''}`}
                onClick={() => toggleTool(tool.id)}
                role="checkbox"
                aria-checked={isSelected}
                tabIndex={0}
                onKeyDown={e => e.key === 'Enter' && toggleTool(tool.id)}
              >
                <div className="setup-tool-icon">{tool.icon}</div>
                <div className="setup-tool-name">{tool.name}</div>
                <div className="setup-tool-desc">{tool.desc}</div>
                <div className={`setup-tool-check${isSelected ? ' on' : ''}`}>{isSelected ? '✓' : ''}</div>
              </div>
            )
          })}
        </div>

        <div className="setup-actions">
          <button className="btn btn-ghost btn-sm" onClick={() => setPhase('mode-select')}>
            ← {t('setup_mode_title')}
          </button>
          <button className="btn btn-primary" onClick={handleStartSetup}>
            {t('setup_start')}
          </button>
        </div>
      </div>
    )
  }

  // ── Installing ────────────────────────────────────────────────────────
  if (phase === 'installing') {
    const pct = Math.round(progress * 100)
    return (
      <div className="setup-container">
        <Stepper currentStep={currentStep} />
        <div className="setup-title">{t('setup_setting_up')}</div>
        <div className="setup-progress-wrap">
          <div className="setup-progress-ring">
            <svg viewBox="0 0 80 80" width="80" height="80">
              <circle cx="40" cy="40" r="34" fill="none" stroke="var(--bg-tertiary)" strokeWidth="6" />
              <circle
                cx="40" cy="40" r="34" fill="none"
                stroke="var(--accent)" strokeWidth="6"
                strokeLinecap="round"
                strokeDasharray={`${2 * Math.PI * 34}`}
                strokeDashoffset={`${2 * Math.PI * 34 * (1 - progress)}`}
                transform="rotate(-90 40 40)"
                style={{ transition: 'stroke-dashoffset 0.4s ease' }}
              />
            </svg>
            <div className="setup-progress-pct">{pct}%</div>
          </div>
          <div className="setup-progress-msg">{message}</div>
        </div>
        <TipCard tip={getTips()[tipIndex]} />
      </div>
    )
  }

  // ── Failed ────────────────────────────────────────────────────────────
  if (phase === 'failed') {
    return (
      <div className="setup-container setup-container--scroll">
        <Stepper currentStep={currentStep} />
        <div className="setup-failed-icon">✗</div>
        <div className="setup-title" style={{ color: 'var(--error)' }}>{t('setup_install_failed')}</div>
        <div className="setup-subtitle">{t('setup_failed_hint')}</div>

        {error && (
          <div className="setup-error-detail">
            <div className="setup-error-label">Error</div>
            <div className="setup-error-text">{error}</div>
          </div>
        )}
        {message && (
          <div className="setup-last-action">Last step: {message}</div>
        )}

        {/* Connection check */}
        <div className="setup-conn-check">
          <button
            className="btn btn-secondary btn-sm"
            onClick={handleCheckConnection}
            disabled={checkingConn}
          >
            {checkingConn
              ? <><span className="spinner" style={{ width: 14, height: 14, marginRight: 6 }} />{t('setup_checking_connection')}</>
              : t('setup_check_connection')
            }
          </button>
          {connResult === 'ok' && (
            <span className="pill pill-success" style={{ marginLeft: 8 }}>{t('setup_connection_ok')}</span>
          )}
          {connResult === 'fail' && (
            <span className="pill pill-error" style={{ marginLeft: 8 }}>{t('setup_connection_failed')}</span>
          )}
        </div>

        <div className="setup-failed-actions">
          <button className="btn btn-secondary" onClick={() => bridge.call('showTerminal')}>
            {t('setup_open_log')}
          </button>
          <button className="btn btn-primary" onClick={handleStartSetup}>
            {t('setup_retry')}
          </button>
        </div>
        <button className="btn btn-ghost btn-sm" onClick={() => setPhase('tool-select')}>
          {t('setup_back_to_tools')}
        </button>
      </div>
    )
  }

  // ── Done ──────────────────────────────────────────────────────────────
  return (
    <div className="setup-container">
      <Stepper currentStep={currentStep} />
      <div className="setup-logo setup-done-icon">✓</div>
      <div className="setup-title">{t('setup_done_title')}</div>
      <div className="setup-subtitle">{t('setup_done_desc')}</div>
      <button className="btn btn-primary btn-full" onClick={() => {
        bridge.call('showTerminal')
        onComplete()
      }}>
        {t('setup_open_terminal')}
      </button>
    </div>
  )
}
