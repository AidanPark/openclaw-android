import { useState, useEffect } from 'react'
import { useRoute } from '../lib/router'
import { bridge } from '../lib/bridge'
import { t } from '../i18n'

interface VersionInfo {
    openclaw: { version: string; installed: boolean; path: string; wrapperExists: boolean; wrapperPath: string }
    node: { version: string; installed: boolean; path: string; wrapperExists: boolean; wrapperPath: string }
    npm: { version: string; installed: boolean; wrapperExists: boolean; wrapperPath: string }
    glibc: { ok: boolean; linkerPath: string; linkerExists: boolean; linkerSizeKb: number }
    installedAt: string
    source: string
    payloadDir: string
    prefixDir: string
    homeDir: string
}

interface PermResult {
    success: boolean
    fixed: number
    skipped: number
    errors: string[]
    error?: string
}

function StatusDot({ ok }: { ok: boolean }) {
    return (
        <span style={{
            display: 'inline-block', width: 8, height: 8, borderRadius: '50%',
            background: ok ? 'var(--success)' : 'var(--error)',
            marginRight: 6, flexShrink: 0,
            boxShadow: ok ? '0 0 5px var(--success)' : undefined,
        }} />
    )
}

function InfoRow({ label, value, mono, ok }: { label: string; value: string; mono?: boolean; ok?: boolean }) {
    return (
        <div className="info-row">
            <span className="label">{label}</span>
            <span style={{
                fontFamily: mono ? 'monospace' : undefined,
                fontSize: mono ? 11 : 13,
                color: ok === false ? 'var(--error)' : ok === true ? 'var(--success)' : 'var(--text-primary)',
                maxWidth: '60%', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                direction: mono ? 'rtl' : undefined, textAlign: mono ? 'right' : undefined,
            }}>
                {ok !== undefined && <StatusDot ok={ok} />}
                {value}
            </span>
        </div>
    )
}

export function SettingsAdvanced() {
    const { navigate } = useRoute()
    const [versions, setVersions] = useState<VersionInfo | null>(null)
    const [loading, setLoading] = useState(true)
    const [fixingPerms, setFixingPerms] = useState(false)
    const [permResult, setPermResult] = useState<PermResult | null>(null)
    const [runningInstall, setRunningInstall] = useState(false)
    const [installLog, setInstallLog] = useState<string[]>([])

    function loadVersions() {
        setLoading(true)
        try {
            const v = bridge.callJson<VersionInfo>('getDetailedVersionInfo')
            if (v) setVersions(v)
        } catch { /* ignore */ }
        setLoading(false)
    }

    useEffect(() => { loadVersions() }, [])

    function handleFixPermissions() {
        setFixingPerms(true)
        setPermResult(null)
        try {
            const result = bridge.callJson<PermResult>('fixScriptPermissions')
            if (result) setPermResult(result)
        } catch (e) {
            setPermResult({ success: false, fixed: 0, skipped: 0, errors: [], error: String(e) })
        }
        setFixingPerms(false)
        // Reload versions after fixing
        setTimeout(loadVersions, 500)
    }

    function handleOnlineInstall() {
        setRunningInstall(true)
        setInstallLog([])
        bridge.call('showTerminal')
        const sessionRaw = bridge.callJson<{ id: string; name?: string }>('createSession')
        const sessionId = sessionRaw?.id || ''
        if (sessionId) {
            setTimeout(() => {
                bridge.call('writeToTerminal', sessionId,
                    'curl -sL myopenclawhub.com/install | bash && source ~/.bashrc\n'
                )
            }, 400)
        }
        setRunningInstall(false)
    }

    function handleRunInTerminal(cmd: string) {
        bridge.call('showTerminal')
        const sessions = bridge.callJson<Array<{ id: string; active: boolean }>>('getTerminalSessions')
        const active = sessions?.find(s => s.active)
        const sessionRaw = bridge.callJson<{ id: string }>('createSession')
        const id = active?.id || sessionRaw?.id || ''
        if (id) {
            setTimeout(() => bridge.call('writeToTerminal', id, cmd + '\n'), 200)
        }
    }

    const installDate = versions?.installedAt
        ? new Date(Number(versions.installedAt)).toLocaleDateString('es-ES', {
            year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit',
        })
        : '—'

    return (
        <div className="page">
            <div className="page-header">
                <button className="back-btn" onClick={() => navigate('/settings')}>←</button>
                <div className="page-title">{t('advanced_title')}</div>
                <button
                    className="btn btn-secondary btn-sm"
                    style={{ marginLeft: 'auto' }}
                    onClick={loadVersions}
                >
                    ↻
                </button>
            </div>

            {loading && (
                <div className="empty-state">
                    <div className="spinner" />
                </div>
            )}

            {!loading && versions && (
                <>
                    {/* ── Versiones ─────────────────────────────────────────── */}
                    <div className="section-title">{t('advanced_versions')}</div>
                    <div className="card">
                        <InfoRow
                            label="Node.js"
                            value={versions.node.version !== 'unknown' ? versions.node.version : t('env_not_detected')}
                            ok={versions.node.installed}
                        />
                        <InfoRow
                            label="npm"
                            value={versions.npm.version !== 'unknown' ? versions.npm.version : t('env_not_detected')}
                            ok={versions.npm.installed}
                        />
                        <InfoRow
                            label="OpenClaw"
                            value={versions.openclaw.version !== 'not installed' ? versions.openclaw.version : t('env_not_detected')}
                            ok={versions.openclaw.installed}
                        />
                        <InfoRow
                            label="glibc linker"
                            value={versions.glibc.ok ? `${versions.glibc.linkerSizeKb} KB` : t('env_not_detected')}
                            ok={versions.glibc.ok}
                        />
                        <InfoRow label={t('advanced_source')} value={versions.source} />
                        <InfoRow label={t('advanced_installed_at')} value={installDate} />
                    </div>

                    {/* ── Wrappers / binarios ───────────────────────────────── */}
                    <div className="section-title">{t('advanced_wrappers')}</div>
                    <div className="card">
                        <InfoRow
                            label="node wrapper"
                            value={versions.node.wrapperExists ? '✓ existe' : '✗ falta'}
                            ok={versions.node.wrapperExists}
                        />
                        <InfoRow
                            label="npm wrapper"
                            value={versions.npm.wrapperExists ? '✓ existe' : '✗ falta'}
                            ok={versions.npm.wrapperExists}
                        />
                        <InfoRow
                            label="openclaw wrapper"
                            value={versions.openclaw.wrapperExists ? '✓ existe' : '✗ falta'}
                            ok={versions.openclaw.wrapperExists}
                        />
                        <InfoRow
                            label="ld-linux-aarch64"
                            value={versions.glibc.linkerExists ? '✓ existe' : '✗ falta'}
                            ok={versions.glibc.linkerExists}
                        />
                    </div>

                    {/* ── Rutas ─────────────────────────────────────────────── */}
                    <div className="section-title">{t('advanced_paths')}</div>
                    <div className="card">
                        <InfoRow label="HOME" value={versions.homeDir} mono />
                        <InfoRow label="PREFIX" value={versions.prefixDir} mono />
                        <InfoRow label="PAYLOAD" value={versions.payloadDir} mono />
                        <InfoRow label="node.real" value={versions.node.path} mono />
                        <InfoRow label="openclaw.mjs" value={versions.openclaw.path} mono />
                        <InfoRow label="glibc linker" value={versions.glibc.linkerPath} mono />
                    </div>
                </>
            )}

            {/* ── Permisos de scripts ───────────────────────────────────── */}
            <div className="section-title">{t('advanced_permissions')}</div>
            <div className="card">
                <div style={{ fontSize: 13, color: 'var(--text-secondary)', marginBottom: 12, lineHeight: 1.6 }}>
                    {t('advanced_permissions_desc')}
                </div>

                {permResult && (
                    <div style={{
                        background: permResult.success ? 'var(--success-dim)' : 'var(--error-dim)',
                        border: `1px solid ${permResult.success ? 'var(--success)' : 'var(--error)'}`,
                        borderRadius: 8, padding: '10px 14px', marginBottom: 12, fontSize: 13,
                    }}>
                        {permResult.success ? (
                            <>
                                <div style={{ color: 'var(--success)', fontWeight: 700, marginBottom: 4 }}>
                                    ✓ {t('advanced_perms_fixed', { n: String(permResult.fixed) })}
                                </div>
                                <div style={{ color: 'var(--text-secondary)', fontSize: 12 }}>
                                    {permResult.skipped} ya tenían permisos correctos
                                </div>
                                {permResult.errors.length > 0 && (
                                    <div style={{ color: 'var(--warning)', fontSize: 12, marginTop: 4 }}>
                                        {permResult.errors.length} errores: {permResult.errors.slice(0, 3).join(', ')}
                                    </div>
                                )}
                            </>
                        ) : (
                            <div style={{ color: 'var(--error)' }}>✗ {permResult.error || 'Error desconocido'}</div>
                        )}
                    </div>
                )}

                <button
                    className="btn btn-secondary"
                    style={{ width: '100%' }}
                    onClick={handleFixPermissions}
                    disabled={fixingPerms}
                >
                    {fixingPerms
                        ? <><span className="spinner" style={{ width: 16, height: 16, marginRight: 8 }} />{t('advanced_fixing_perms')}</>
                        : t('advanced_fix_permissions')}
                </button>
            </div>

            {/* ── Instalación online ────────────────────────────────────── */}
            <div className="section-title">{t('advanced_online_install')}</div>
            <div className="card">
                <div style={{ fontSize: 13, color: 'var(--text-secondary)', marginBottom: 10, lineHeight: 1.6 }}>
                    {t('advanced_online_install_desc')}
                </div>

                {/* Comando curl */}
                <div style={{
                    background: 'var(--bg-primary)', border: '1px solid var(--border)',
                    borderRadius: 8, padding: '10px 14px', marginBottom: 12,
                    fontFamily: 'monospace', fontSize: 12, color: 'var(--text-secondary)',
                    wordBreak: 'break-all', lineHeight: 1.7,
                    position: 'relative',
                }}>
                    curl -sL myopenclawhub.com/install | bash
                    <button
                        className="copy-btn"
                        onClick={() => bridge.call('copyToClipboard', 'curl -sL myopenclawhub.com/install | bash')}
                    >
                        {t('ka_copy')}
                    </button>
                </div>

                <button
                    className="btn btn-primary"
                    style={{ width: '100%' }}
                    onClick={handleOnlineInstall}
                    disabled={runningInstall}
                >
                    {runningInstall
                        ? <><span className="spinner" style={{ width: 16, height: 16, marginRight: 8 }} />Ejecutando...</>
                        : `▶ ${t('advanced_run_install')}`}
                </button>
            </div>

            {/* ── Comandos de diagnóstico ───────────────────────────────── */}
            <div className="section-title">{t('advanced_diagnostics')}</div>
            <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
                {[
                    { label: 'node --version', cmd: 'node --version', icon: '⬢' },
                    { label: 'npm --version', cmd: 'npm --version', icon: '📦' },
                    { label: 'openclaw --version', cmd: 'openclaw --version', icon: '🦀' },
                    { label: 'openclaw status', cmd: 'openclaw status', icon: '◉' },
                    { label: 'openclaw onboard', cmd: 'openclaw onboard', icon: '✦' },
                    { label: 'openclaw gateway --host 0.0.0.0', cmd: 'openclaw gateway --host 0.0.0.0', icon: '▶' },
                    { label: 'ls ~/.openclaw-android/bin/', cmd: 'ls -la ~/.openclaw-android/bin/', icon: '📂' },
                    { label: 'echo $PATH', cmd: 'echo $PATH', icon: '🔍' },
                ].map((item, i) => (
                    <div
                        key={item.cmd}
                        role="button"
                        tabIndex={0}
                        onClick={() => handleRunInTerminal(item.cmd)}
                        onKeyDown={e => e.key === 'Enter' && handleRunInTerminal(item.cmd)}
                        style={{
                            display: 'flex', alignItems: 'center', gap: 12,
                            padding: '12px 16px',
                            borderTop: i > 0 ? '1px solid var(--border-subtle)' : 'none',
                            cursor: 'pointer',
                        }}
                    >
                        <span style={{
                            width: 32, height: 32, borderRadius: 8,
                            background: 'var(--bg-tertiary)',
                            display: 'flex', alignItems: 'center', justifyContent: 'center',
                            fontSize: 14, flexShrink: 0,
                        }}>
                            {item.icon}
                        </span>
                        <div style={{ flex: 1, minWidth: 0 }}>
                            <div style={{ fontSize: 13, fontFamily: 'monospace', color: 'var(--text-primary)' }}>
                                {item.label}
                            </div>
                        </div>
                        <span style={{ color: 'var(--text-muted)', fontSize: 16 }}>›</span>
                    </div>
                ))}
            </div>

            {/* ── Logs ─────────────────────────────────────────────────── */}
            {installLog.length > 0 && (
                <>
                    <div className="section-title">Log</div>
                    <div style={{
                        background: 'var(--bg-primary)', border: '1px solid var(--border)',
                        borderRadius: 8, padding: 12, maxHeight: 200, overflowY: 'auto',
                        fontFamily: 'monospace', fontSize: 11, color: 'var(--text-secondary)',
                        lineHeight: 1.6,
                    }}>
                        {installLog.map((line, i) => <div key={i}>{line}</div>)}
                    </div>
                </>
            )}

            <div style={{ height: 24 }} />
        </div>
    )
}
