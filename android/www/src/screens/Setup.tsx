import { memo, useState, useCallback, useEffect } from 'react'
import { bridge } from '../lib/bridge'
import { useNativeEvent } from '../lib/useNativeEvent'
import { t } from '../i18n'

interface Props {
  onComplete: () => void
}

/**
 * Installation flow:
 *
 *  Environment selector (new):
 *    → "termux"  → Termux Bootstrap (2-step flow, existing)
 *    → "proot"   → Proot + Ubuntu (1-step online install)
 *
 *  Termux flow (2 steps):
 *    Step 1: Termux Bootstrap  → mode "termux-bootstrap"
 *    Step 2: OpenClaw runtime  → mode "online" | "offline"
 *
 *  Proot flow (1 step):
 *    Step 1: Proot + Ubuntu    → mode "proot"
 *            Downloads proot binary + Ubuntu rootfs (~80MB)
 *            Then opens terminal for online OpenClaw install
 */

type EnvChoice = 'none' | 'termux' | 'proot'
type BootstrapPhase = 'idle' | 'installing' | 'done' | 'failed'
type OpenClawPhase = 'locked' | 'mode-select' | 'tool-select' | 'installing' | 'done' | 'failed'
type ProotPhase = 'idle' | 'installing' | 'done' | 'failed'

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

// ── Progress ring ─────────────────────────────────────────────────────────
const ProgressRing = memo(function ProgressRing({ progress, label }: { progress: number; label: string }) {
  const r = 34
  const circ = 2 * Math.PI * r
  return (
    <div className="setup-progress-ring">
      <svg viewBox="0 0 80 80" width="80" height="80">
        <circle cx="40" cy="40" r={r} fill="none" stroke="var(--bg-tertiary)" strokeWidth="6" />
        <circle
          cx="40" cy="40" r={r} fill="none"
          stroke="var(--accent)" strokeWidth="6"
          strokeLinecap="round"
          strokeDasharray={`${circ}`}
          strokeDashoffset={`${circ * (1 - progress)}`}
          transform="rotate(-90 40 40)"
          style={{ transition: 'stroke-dashoffset 0.4s ease' }}
        />
      </svg>
      <div className="setup-progress-pct">{label}</div>
    </div>
  )
})

// ── Main component ────────────────────────────────────────────────────────
export function Setup({ onComplete }: Props) {
  // ── Environment choice ────────────────────────────────────────────────
  const [envChoice, setEnvChoice] = useState<EnvChoice>('none')

  // ── Proot flow ────────────────────────────────────────────────────────
  const [prootPhase, setProotPhase] = useState<ProotPhase>('idle')
  const [prootProgress, setProotProgress] = useState(0)
  const [prootMessage, setProotMessage] = useState('')
  const [prootError, setProotError] = useState('')

  // ── Step 1: Bootstrap (Termux flow) ───────────────────────────────────
  const [bsPhase, setBsPhase] = useState<BootstrapPhase>('idle')
  const [bsProgress, setBsProgress] = useState(0)
  const [bsMessage, setBsMessage] = useState('')
  const [bsError, setBsError] = useState('')

  // ── Step 2: OpenClaw ──────────────────────────────────────────────────
  const [ocPhase, setOcPhase] = useState<OpenClawPhase>('locked')
  const [ocProgress, setOcProgress] = useState(0)
  const [ocMessage, setOcMessage] = useState('')
  const [ocError, setOcError] = useState('')
  const [installMode, setInstallMode] = useState<'online' | 'offline'>('online')
  const [hasAsset, setHasAsset] = useState(false)
  const [selectedFileName, setSelectedFileName] = useState<string | null>(null)
  const [selectedTools, setSelectedTools] = useState<Set<string>>(new Set())

  // ── Tips ──────────────────────────────────────────────────────────────
  const [tipIndex, setTipIndex] = useState(0)
  const [autoNavigate, setAutoNavigate] = useState(false)
  const isInstalling = bsPhase === 'installing' || ocPhase === 'installing' || prootPhase === 'installing'
  useEffect(() => {
    if (!isInstalling) return
    const id = setInterval(() => setTipIndex(i => (i + 1) % getTips().length), 4000)
    return () => clearInterval(id)
  }, [isInstalling])

  // Auto-navigate to dashboard after bootstrap completes (3s delay)
  // Cancelled if user proceeds to Step 2 (ocPhase changes from 'mode-select')
  useEffect(() => {
    if (!autoNavigate) return
    if (ocPhase !== 'mode-select') return // user already started Step 2, don't redirect
    onComplete()
  }, [autoNavigate, ocPhase, onComplete])

  // ── Check existing installs on mount ──────────────────────────────────
  useEffect(() => {
    const status = bridge.callJson<{ installed: boolean }>('getBootstrapStatus')
    if (status?.installed) {
      setEnvChoice('termux')
      setBsPhase('done')
      setOcPhase('mode-select')
    }
    const setupStatus = bridge.callJson<{ prootReady?: boolean }>('getSetupStatus')
    if (setupStatus?.prootReady) {
      setEnvChoice('proot')
      setProotPhase('done')
    }
    const d = bridge.callJson<{ hasPayload: boolean }>('hasPayloadAsset')
    if (d) {
      setHasAsset(!!d.hasPayload)
      if (d.hasPayload) setInstallMode('offline')
    }
  }, [])

  // ── Native events ─────────────────────────────────────────────────────
  const onPayloadSelected = useCallback((data: unknown) => {
    const d = data as { name: string }
    if (d.name) setSelectedFileName(d.name)
  }, [])
  useNativeEvent('payload_file_selected', onPayloadSelected)

  // setup_progress events — route to the active flow
  const onProgress = useCallback((data: unknown) => {
    const d = data as { progress?: number; message?: string; error?: string }
    const pct = d.progress ?? 0

    // Proot flow
    if (envChoice === 'proot' && prootPhase === 'installing') {
      if (d.progress !== undefined) setProotProgress(pct)
      if (d.message) setProotMessage(d.message)
      if (d.error) { setProotError(d.error); setProotPhase('failed') }
      else if (pct >= 1) { setProotPhase('done') }
      return
    }

    // Termux flow — bootstrap step
    if (bsPhase === 'installing') {
      if (d.progress !== undefined) setBsProgress(pct)
      if (d.message) setBsMessage(d.message)
      if (d.error) { setBsError(d.error); setBsPhase('failed') }
      else if (pct >= 1) {
        setBsPhase('done')
        setOcPhase('mode-select')
        setTimeout(() => setAutoNavigate(true), 3000)
      }
    } else if (ocPhase === 'installing') {
      // Termux flow — openclaw step
      if (d.progress !== undefined) setOcProgress(pct)
      if (d.message) setOcMessage(d.message)
      if (d.error) { setOcError(d.error); setOcPhase('failed') }
      else if (pct >= 1) { setOcPhase('done') }
    }
  }, [envChoice, prootPhase, bsPhase, ocPhase])
  useNativeEvent('setup_progress', onProgress)

  // ── Handlers ──────────────────────────────────────────────────────────
  const handleInstallBootstrap = useCallback(() => {
    setBsPhase('installing')
    setBsProgress(0)
    setBsMessage(t('setup_bootstrap_installing'))
    setBsError('')
    bridge.call('startSetup', 'termux-bootstrap')
  }, [])

  const handleInstallProot = useCallback(() => {
    setProotPhase('installing')
    setProotProgress(0)
    setProotMessage(t('setup_proot_installing'))
    setProotError('')
    bridge.call('startSetup', 'proot')
  }, [])

  const handleStartOpenClaw = useCallback(() => {
    const selections: Record<string, boolean> = {}
    getOptionalTools().forEach(tool => {
      selections[tool.id] = selectedTools.has(tool.id)
    })
    bridge.call('saveToolSelections', JSON.stringify(selections))
    bridge.call('saveInstallPath', 'local')

    setOcPhase('installing')
    setOcProgress(0)
    setOcMessage(t('setup_preparing'))
    setOcError('')
    bridge.call('startSetup', installMode)
  }, [selectedTools, installMode])

  const toggleTool = useCallback((id: string) => {
    setSelectedTools(prev => {
      const next = new Set(prev)
      if (next.has(id)) { next.delete(id) } else { next.add(id) }
      return next
    })
  }, [])

  // ── Success screens ───────────────────────────────────────────────────
  if (envChoice === 'termux' && ocPhase === 'done') {
    return <SuccessScreen onComplete={onComplete} />
  }
  if (envChoice === 'proot' && prootPhase === 'done') {
    return <ProotSuccessScreen onComplete={onComplete} />
  }

  // ── Environment selector ──────────────────────────────────────────────
  if (envChoice === 'none') {
    return (
      <div className="setup-container setup-container--scroll">
        <div className="setup-title" style={{ marginBottom: 6 }}>{t('setup_choose_env')}</div>
        <div className="setup-subtitle" style={{ marginBottom: 20 }}>{t('setup_choose_env_desc')}</div>

        {/* Termux */}
        <div
          className="card clickable"
          onClick={() => setEnvChoice('termux')}
          style={{ width: '100%', maxWidth: 420, marginBottom: 12 }}
        >
          <div className="card-row">
            <span style={{ fontSize: 28, lineHeight: 1, flexShrink: 0 }}>🐚</span>
            <div className="card-content" style={{ marginLeft: 12 }}>
              <div className="card-label" style={{ fontSize: 15 }}>{t('setup_env_termux_label')}</div>
              <div className="card-desc">{t('setup_env_termux_desc')}</div>
              <div style={{ marginTop: 6, display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                <span className="pill pill-info" style={{ fontSize: 11, padding: '2px 8px' }}>~30 MB</span>
                <span className="pill pill-success" style={{ fontSize: 11, padding: '2px 8px' }}>{t('setup_env_recommended')}</span>
              </div>
            </div>
            <span style={{ color: 'var(--text-muted)', fontSize: 20, marginLeft: 8 }}>›</span>
          </div>
        </div>

        {/* Proot */}
        <div
          className="card clickable"
          onClick={() => setEnvChoice('proot')}
          style={{ width: '100%', maxWidth: 420 }}
        >
          <div className="card-row">
            <span style={{ fontSize: 28, lineHeight: 1, flexShrink: 0 }}>🐧</span>
            <div className="card-content" style={{ marginLeft: 12 }}>
              <div className="card-label" style={{ fontSize: 15 }}>{t('setup_env_proot_label')}</div>
              <div className="card-desc">{t('setup_env_proot_desc')}</div>
              <div style={{ marginTop: 6, display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                <span className="pill pill-info" style={{ fontSize: 11, padding: '2px 8px' }}>~80 MB</span>
                <span className="pill" style={{ fontSize: 11, padding: '2px 8px', background: 'var(--bg-tertiary)', color: 'var(--text-muted)' }}>Ubuntu 22.04</span>
              </div>
            </div>
            <span style={{ color: 'var(--text-muted)', fontSize: 20, marginLeft: 8 }}>›</span>
          </div>
        </div>
      </div>
    )
  }

  // ── Proot flow ────────────────────────────────────────────────────────
  if (envChoice === 'proot') {
    return (
      <div className="setup-container setup-container--scroll">
        {prootPhase === 'idle' && (
          <button
            className="btn btn-ghost btn-sm"
            style={{ alignSelf: 'flex-start', marginBottom: 12 }}
            onClick={() => setEnvChoice('none')}
          >
            ← {t('setup_back')}
          </button>
        )}

        <StepCard number={1} title={t('setup_proot_title')} done={prootPhase === 'done'} active={prootPhase !== 'done'}>
          {prootPhase === 'idle' && (
            <>
              <p className="setup-subtitle" style={{ marginBottom: 12 }}>{t('setup_proot_desc')}</p>
              <div style={{
                background: 'var(--bg-primary)', border: '1px solid var(--border)',
                borderRadius: 8, padding: '10px 14px', marginBottom: 14,
              }}>
                {[t('setup_proot_feat_1'), t('setup_proot_feat_2'), t('setup_proot_feat_3')].map((f, i) => (
                  <div key={i} style={{
                    fontSize: 12, color: 'var(--text-secondary)', lineHeight: 1.7,
                    borderTop: i > 0 ? '1px solid var(--border-subtle)' : 'none',
                    paddingTop: i > 0 ? 5 : 0, marginTop: i > 0 ? 5 : 0,
                  }}>✓ {f}</div>
                ))}
              </div>
              <div style={{ fontSize: 12, color: 'var(--text-muted)', marginBottom: 14 }}>
                ⚠️ {t('setup_proot_size')}
              </div>
              <button className="btn btn-primary btn-full" onClick={handleInstallProot}>
                🐧 {t('setup_proot_btn')}
              </button>
            </>
          )}

          {prootPhase === 'installing' && (
            <div className="setup-progress-wrap">
              <ProgressRing progress={prootProgress} label={`${Math.round(prootProgress * 100)}%`} />
              <div className="setup-progress-msg">{prootMessage}</div>
              <TipCard tip={getTips()[tipIndex]} />
            </div>
          )}

          {prootPhase === 'done' && (
            <div className="pill pill-success" style={{ padding: '8px 16px', fontSize: 14 }}>
              ✓ {t('setup_proot_done')}
            </div>
          )}

          {prootPhase === 'failed' && (
            <div style={{ width: '100%' }}>
              <div style={{ color: 'var(--error)', marginBottom: 8 }}>{t('setup_install_failed')}</div>
              {prootError && <div className="setup-error-text" style={{ marginBottom: 12 }}>{prootError}</div>}
              <div style={{ display: 'flex', gap: 8 }}>
                <button className="btn btn-secondary btn-sm" onClick={() => bridge.call('showTerminal')}>
                  {t('setup_open_log')}
                </button>
                <button className="btn btn-primary btn-sm" onClick={handleInstallProot}>
                  {t('setup_retry')}
                </button>
              </div>
            </div>
          )}
        </StepCard>
      </div>
    )
  }

  // ── Termux flow ───────────────────────────────────────────────────────
  // Bootstrap (Step 1) is OPTIONAL for offline install — the payload extracts
  // directly to homeDir without needing bash/apt. It is only required for
  // the online (curl | bash) install mode.
  // We unlock Step 2 immediately so the user can pick offline without Step 1.

  return (
    <div className="setup-container setup-container--scroll">
      {/* Back to env selector only if nothing installed yet */}
      {bsPhase === 'idle' && ocPhase === 'locked' && (
        <button
          className="btn btn-ghost btn-sm"
          style={{ alignSelf: 'flex-start', marginBottom: 12 }}
          onClick={() => setEnvChoice('none')}
        >
          ← {t('setup_back')}
        </button>
      )}

      {/* ── STEP 1: Termux Bootstrap (optional for offline) ──────────── */}
      <StepCard
        number={1}
        title={t('setup_bootstrap_title')}
        done={bsPhase === 'done'}
        active={bsPhase !== 'done'}
      >
        {bsPhase === 'idle' && (
          <>
            <p className="setup-subtitle" style={{ marginBottom: 8 }}>{t('setup_bootstrap_desc')}</p>
            {/* Optional badge */}
            <div style={{
              display: 'inline-flex', alignItems: 'center', gap: 6,
              background: 'var(--warning-dim, rgba(255,180,0,0.12))',
              border: '1px solid var(--warning, #ffb400)',
              borderRadius: 6, padding: '4px 10px', marginBottom: 12, fontSize: 12,
              color: 'var(--warning, #ffb400)',
            }}>
              ⚡ {t('setup_bootstrap_optional')}
            </div>
            <div style={{ fontSize: 12, color: 'var(--text-muted)', marginBottom: 14 }}>
              {t('setup_bootstrap_size')}
            </div>
            <button className="btn btn-primary btn-full" onClick={handleInstallBootstrap}>
              {t('setup_bootstrap_btn')}
            </button>
            <button
              className="btn btn-ghost btn-sm btn-full"
              style={{ marginTop: 8 }}
              onClick={() => setOcPhase('mode-select')}
            >
              {t('setup_bootstrap_skip')} →
            </button>
          </>
        )}

        {bsPhase === 'installing' && (
          <div className="setup-progress-wrap">
            <ProgressRing progress={bsProgress} label={`${Math.round(bsProgress * 100)}%`} />
            <div className="setup-progress-msg">{bsMessage}</div>
            <TipCard tip={getTips()[tipIndex]} />
          </div>
        )}

        {bsPhase === 'done' && (
          <div className="pill pill-success" style={{ padding: '8px 16px', fontSize: 14 }}>
            {t('setup_bootstrap_done')}
          </div>
        )}

        {/* Skipped state — user went directly to Step 2 */}
        {bsPhase === 'idle' && ocPhase !== 'locked' && (
          <div style={{ fontSize: 12, color: 'var(--text-muted)', fontStyle: 'italic' }}>
            {t('setup_bootstrap_skipped')}
          </div>
        )}

        {bsPhase === 'failed' && (
          <div style={{ width: '100%' }}>
            <div style={{ color: 'var(--error)', marginBottom: 8 }}>{t('setup_install_failed')}</div>
            {bsError && <div className="setup-error-text" style={{ marginBottom: 12 }}>{bsError}</div>}
            <div style={{ display: 'flex', gap: 8 }}>
              <button className="btn btn-secondary btn-sm" onClick={() => bridge.call('showTerminal')}>
                {t('setup_open_log')}
              </button>
              <button className="btn btn-primary btn-sm" onClick={handleInstallBootstrap}>
                {t('setup_retry')}
              </button>
            </div>
          </div>
        )}
      </StepCard>

      {/* ── STEP 2: OpenClaw ─────────────────────────────────────────── */}
      <StepCard
        number={2}
        title={t('setup_openclaw_title')}
        done={ocPhase === 'done'}
        active={ocPhase !== 'locked' || bsPhase === 'done'}
        locked={ocPhase === 'locked' && bsPhase === 'idle'}
      >
        {/* Locked — show only when user hasn't clicked skip or installed bootstrap */}
        {ocPhase === 'locked' && bsPhase === 'idle' && (
          <p style={{ fontSize: 13, color: 'var(--text-muted)' }}>{t('setup_openclaw_locked_or_skip')}</p>
        )}

        {(ocPhase === 'mode-select' || (bsPhase === 'done' && ocPhase === 'locked')) && (() => {
          // Auto-unlock when bootstrap finishes
          if (bsPhase === 'done' && ocPhase === 'locked') setOcPhase('mode-select')
          return null
        })()}

        {ocPhase === 'mode-select' && (
          <>
            <p className="setup-subtitle" style={{ marginBottom: 12 }}>{t('setup_openclaw_desc')}</p>
            <div className="card-group" style={{ width: '100%' }}>
              {/* Online */}
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
                    {/* Warn if bootstrap not installed */}
                    {bsPhase !== 'done' && (
                      <div style={{
                        marginTop: 8, fontSize: 12,
                        color: 'var(--warning, #ffb400)',
                        background: 'var(--warning-dim, rgba(255,180,0,0.1))',
                        border: '1px solid var(--warning, #ffb400)',
                        borderRadius: 6, padding: '6px 10px',
                      }}>
                        ⚠️ {t('setup_online_needs_bootstrap')}
                      </div>
                    )}
                  </div>
                )}
              </div>

              {/* Offline */}
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
            <button
              className="btn btn-primary btn-full"
              style={{ marginTop: 16 }}
              onClick={() => setOcPhase('tool-select')}
              disabled={
                (installMode === 'offline' && !hasAsset && !selectedFileName) ||
                (installMode === 'online' && bsPhase !== 'done')
              }
            >
              {t('setup_next')} →
            </button>
            {/* If online is blocked, offer to go install bootstrap */}
            {installMode === 'online' && bsPhase !== 'done' && (
              <button
                className="btn btn-secondary btn-sm btn-full"
                style={{ marginTop: 8 }}
                onClick={() => { setOcPhase('locked'); setBsPhase('idle') }}
              >
                ← {t('setup_go_install_bootstrap')}
              </button>
            )}
          </>
        )}

        {ocPhase === 'tool-select' && (
          <>
            <div className="setup-title" style={{ fontSize: 18, marginBottom: 4 }}>{t('setup_optional_tools')}</div>
            <div className="setup-subtitle" style={{ marginBottom: 12 }}>{t('setup_tools_desc', { platform: 'OpenClaw' })}</div>
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
            <div className="setup-actions" style={{ marginTop: 16 }}>
              <button className="btn btn-ghost btn-sm" onClick={() => setOcPhase('mode-select')}>
                ← {t('setup_mode_title')}
              </button>
              <button className="btn btn-primary" onClick={handleStartOpenClaw}>
                {t('setup_start')}
              </button>
            </div>
          </>
        )}

        {ocPhase === 'installing' && (
          <div className="setup-progress-wrap">
            <ProgressRing progress={ocProgress} label={`${Math.round(ocProgress * 100)}%`} />
            <div className="setup-progress-msg">{ocMessage}</div>
            <TipCard tip={getTips()[tipIndex]} />
          </div>
        )}

        {ocPhase === 'done' && (
          <div className="pill pill-success" style={{ padding: '8px 16px', fontSize: 14 }}>
            ✓ OpenClaw instalado
          </div>
        )}

        {ocPhase === 'failed' && (
          <div style={{ width: '100%' }}>
            <div style={{ color: 'var(--error)', marginBottom: 8 }}>{t('setup_install_failed')}</div>
            {ocError && <div className="setup-error-text" style={{ marginBottom: 12 }}>{ocError}</div>}
            <div style={{ display: 'flex', gap: 8 }}>
              <button className="btn btn-secondary btn-sm" onClick={() => bridge.call('showTerminal')}>
                {t('setup_open_log')}
              </button>
              <button className="btn btn-primary btn-sm" onClick={handleStartOpenClaw}>
                {t('setup_retry')}
              </button>
            </div>
          </div>
        )}
      </StepCard>
    </div>
  )
}

// ── SuccessScreen (Termux) ────────────────────────────────────────────────
function SuccessScreen({ onComplete }: { onComplete: () => void }) {
  const [countdown, setCountdown] = useState(3)

  useEffect(() => {
    const id = setInterval(() => {
      setCountdown(prev => {
        if (prev <= 1) {
          clearInterval(id)
          onComplete()
          return 0
        }
        return prev - 1
      })
    }, 1000)
    return () => clearInterval(id)
  }, [onComplete])

  return (
    <div className="setup-container">
      <div className="setup-logo setup-done-icon" style={{ color: 'var(--success)', fontSize: 64 }}>✓</div>
      <div className="setup-title" style={{ color: 'var(--success)' }}>{t('setup_success_title')}</div>
      <div className="setup-subtitle">{t('setup_success_desc')}</div>
      <div style={{ marginTop: 16, marginBottom: 24, display: 'flex', flexDirection: 'column', gap: 8, width: '100%', maxWidth: 360 }}>
        <div className="pill pill-success" style={{ padding: '8px 16px', fontSize: 14 }}>
          ✓ {t('setup_bootstrap_done')}
        </div>
        <div className="pill pill-success" style={{ padding: '8px 16px', fontSize: 14 }}>
          ✓ OpenClaw instalado
        </div>
      </div>
      <div style={{ fontSize: 13, color: 'var(--text-muted)', marginBottom: 16 }}>
        {t('setup_redirecting', { seconds: String(countdown) })}
      </div>
      <button className="btn btn-primary btn-full" onClick={onComplete}>
        {t('setup_go_dashboard')}
      </button>
    </div>
  )
}

// ── ProotSuccessScreen ────────────────────────────────────────────────────
function ProotSuccessScreen({ onComplete }: { onComplete: () => void }) {
  const [countdown, setCountdown] = useState(3)
  useEffect(() => {
    const id = setInterval(() => {
      setCountdown(prev => {
        if (prev <= 1) { clearInterval(id); onComplete(); return 0 }
        return prev - 1
      })
    }, 1000)
    return () => clearInterval(id)
  }, [onComplete])

  return (
    <div className="setup-container">
      <div style={{ fontSize: 64 }}>🐧</div>
      <div className="setup-title" style={{ color: 'var(--success)' }}>{t('setup_proot_success_title')}</div>
      <div className="setup-subtitle">{t('setup_proot_success_desc')}</div>
      <div style={{ marginTop: 16, marginBottom: 24, display: 'flex', flexDirection: 'column', gap: 8, width: '100%', maxWidth: 360 }}>
        <div className="pill pill-success" style={{ padding: '8px 16px', fontSize: 14 }}>
          ✓ {t('setup_proot_done')}
        </div>
      </div>
      <div style={{ fontSize: 13, color: 'var(--text-muted)', marginBottom: 16 }}>
        {t('setup_redirecting', { seconds: String(countdown) })}
      </div>
      <button className="btn btn-primary btn-full" onClick={onComplete}>
        {t('setup_go_dashboard')}
      </button>
    </div>
  )
}

// ── StepCard ──────────────────────────────────────────────────────────────
interface StepCardProps {
  number: number
  title: string
  done?: boolean
  active?: boolean
  locked?: boolean
  children: React.ReactNode
}

function StepCard({ number, title, done, active, locked, children }: StepCardProps) {
  const borderColor = done
    ? 'var(--success)'
    : active
      ? 'var(--accent)'
      : 'var(--border)'

  return (
    <div style={{
      border: `2px solid ${borderColor}`,
      borderRadius: 12,
      padding: 20,
      marginBottom: 16,
      width: '100%',
      maxWidth: 420,
      opacity: locked ? 0.5 : 1,
      transition: 'border-color 0.3s, opacity 0.3s',
      background: 'var(--bg-secondary)',
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 14 }}>
        <div style={{
          width: 28, height: 28, borderRadius: '50%',
          background: done ? 'var(--success)' : active ? 'var(--accent)' : 'var(--bg-tertiary)',
          color: done || active ? '#fff' : 'var(--text-muted)',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          fontWeight: 700, fontSize: 14, flexShrink: 0,
          transition: 'background 0.3s',
        }}>
          {done ? '✓' : number}
        </div>
        <div style={{ fontWeight: 600, fontSize: 16, color: locked ? 'var(--text-muted)' : 'var(--text-primary)' }}>
          {title}
        </div>
      </div>
      {children}
    </div>
  )
}
