import { useState, useEffect } from 'react'
import { useRoute } from '../lib/router'
import { bridge } from '../lib/bridge'
import { t } from '../i18n'

interface StorageInfo {
  // Device-level
  total: number
  free: number
  used: number
  totalMb: number
  freeMb: number
  usedMb: number
  // App-level (actual files in sandbox)
  appUsedBytes: number
  appUsedMb: number
  // Legacy fields (may be 0 if not reported)
  totalBytes?: number
  freeBytes?: number
  bootstrapBytes?: number
  wwwBytes?: number
  payloadBytes?: number
  cacheBytes?: number
  nodeBytes?: number
  openclawBytes?: number
}

function formatBytes(bytes: number): string {
  if (!bytes || bytes <= 0) return '0 B'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`
}

function pct(part: number, total: number): string {
  if (!total || total <= 0) return '—'
  const p = (part / total) * 100
  return p < 0.1 ? '<0.1%' : `${p.toFixed(1)}%`
}

interface BarSegment { label: string; bytes: number; color: string }

function StorageBar({ segments, total }: { segments: BarSegment[]; total: number }) {
  return (
    <div>
      <div style={{
        height: 10, background: 'var(--bg-tertiary)', borderRadius: 5,
        overflow: 'hidden', display: 'flex',
      }}>
        {segments.map((seg, i) => (
          <div key={i} style={{
            width: `${Math.min(100, (seg.bytes / total) * 100)}%`,
            background: seg.color,
            transition: 'width 0.4s',
            minWidth: seg.bytes > 0 ? 2 : 0,
          }} />
        ))}
      </div>
      <div style={{ display: 'flex', gap: 12, marginTop: 10, flexWrap: 'wrap' }}>
        {segments.filter(s => s.bytes > 0).map((seg, i) => (
          <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 5, fontSize: 11 }}>
            <div style={{ width: 8, height: 8, borderRadius: 2, background: seg.color, flexShrink: 0 }} />
            <span style={{ color: 'var(--text-secondary)' }}>{seg.label}</span>
            <span style={{ color: 'var(--text-muted)', fontFamily: 'monospace' }}>
              {formatBytes(seg.bytes)}
            </span>
          </div>
        ))}
      </div>
    </div>
  )
}

export function SettingsStorage() {
  const { navigate } = useRoute()
  const [info, setInfo] = useState<StorageInfo | null>(null)
  const [clearing, setClearing] = useState(false)
  const [error, setError] = useState<string | null>(null)

  function loadInfo() {
    setError(null)
    try {
      const raw = bridge.callJson<Record<string, number>>('getStorageInfo')
      if (raw && typeof raw === 'object') {
        const data: StorageInfo = {
          total: Number(raw.total) || 0,
          free: Number(raw.free) || 0,
          used: Number(raw.used) || 0,
          totalMb: Number(raw.totalMb) || 0,
          freeMb: Number(raw.freeMb) || 0,
          usedMb: Number(raw.usedMb) || 0,
          appUsedBytes: Number(raw.appUsedBytes) || 0,
          appUsedMb: Number(raw.appUsedMb) || 0,
          // legacy fields
          totalBytes: Number(raw.totalBytes) || Number(raw.total) || 0,
          freeBytes: Number(raw.freeBytes) || Number(raw.free) || 0,
          bootstrapBytes: Number(raw.bootstrapBytes) || 0,
          wwwBytes: Number(raw.wwwBytes) || 0,
          payloadBytes: Number(raw.payloadBytes) || 0,
          cacheBytes: Number(raw.cacheBytes) || 0,
          nodeBytes: Number(raw.nodeBytes) || 0,
          openclawBytes: Number(raw.openclawBytes) || 0,
        }
        setInfo(data)
      } else {
        setError('No se pudo obtener información de almacenamiento')
      }
    } catch (e) {
      setError(`Error: ${e}`)
    }

    try {
      const bs = bridge.callJson<{ source?: string }>('getBootstrapStatus')
      if (bs?.source) { /* source info available but not displayed in simplified view */ void bs.source }
    } catch { /* ignore */ }
  }

  useEffect(() => { loadInfo() }, [])

  function handleClearCache() {
    setClearing(true)
    bridge.call('clearCache')
    setTimeout(() => {
      setClearing(false)
      loadInfo()
    }, 2000)
  }

  // Compute totals — use appUsedBytes (real app usage) as primary
  const appUsedBytes = info?.appUsedBytes || 0
  const diskTotal = info?.total || 1
  const diskFree = info?.free || 0

  const segments: BarSegment[] = info ? [
    { label: 'App instalada', bytes: appUsedBytes, color: '#58a6ff' },
  ] : []

  return (
    <div className="page">
      <div className="page-header">
        <button className="back-btn" onClick={() => navigate('/settings')}>←</button>
        <div className="page-title">{t('storage_title')}</div>
      </div>

      {/* Error state */}
      {error && (
        <div className="card" style={{
          background: 'var(--error-dim)', border: '1px solid var(--error)',
          marginBottom: 16,
        }}>
          <div style={{ fontSize: 13, color: 'var(--error)', marginBottom: 8 }}>⚠ {error}</div>
          <button className="btn btn-secondary btn-sm" onClick={loadInfo}>Reintentar</button>
        </div>
      )}

      {/* Loading */}
      {!info && !error && (
        <div className="empty-state">
          <div className="spinner" />
          <div className="empty-state-text">{t('storage_loading')}</div>
        </div>
      )}

      {info && (
        <>
          {/* Resumen: uso real de la app */}
          <div className="card" style={{ marginBottom: 16 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 12 }}>
              <div style={{ fontSize: 13, color: 'var(--text-secondary)' }}>
                Usado por la app
              </div>
              <div style={{ fontSize: 20, fontWeight: 800, fontFamily: 'monospace' }}>
                {formatBytes(appUsedBytes)}
              </div>
            </div>
            <StorageBar segments={segments} total={diskTotal} />
            <div style={{ marginTop: 10, fontSize: 12, color: 'var(--text-muted)' }}>
              {pct(appUsedBytes, diskTotal)} del almacenamiento del dispositivo
            </div>
          </div>

          {/* Espacio libre */}
          <div className="card" style={{ marginBottom: 8 }}>
            <div className="card-row" style={{ cursor: 'default' }}>
              <div className="card-content">
                <div className="card-label">{t('storage_free')}</div>
                <div className="card-desc">Disponible en el dispositivo</div>
              </div>
              <div style={{ textAlign: 'right' }}>
                <div style={{ fontSize: 14, fontWeight: 700, fontFamily: 'monospace' }}>
                  {formatBytes(diskFree)}
                </div>
                <span className="pill pill-success" style={{ fontSize: 11 }}>
                  {pct(diskFree, diskTotal)}
                </span>
              </div>
            </div>
          </div>

          {/* Disco total */}
          <div className="card" style={{ marginBottom: 24 }}>
            <div className="card-row" style={{ cursor: 'default' }}>
              <div className="card-content">
                <div className="card-label">Almacenamiento total</div>
                <div className="card-desc">Capacidad del dispositivo</div>
              </div>
              <span style={{ fontSize: 14, fontWeight: 700, fontFamily: 'monospace', color: 'var(--text-secondary)' }}>
                {formatBytes(diskTotal)}
              </span>
            </div>
          </div>

          {/* Acción */}
          <button
            className="btn btn-secondary"
            style={{ width: '100%' }}
            onClick={handleClearCache}
            disabled={clearing}
          >
            {clearing ? (
              <><span className="spinner" style={{ width: 16, height: 16, marginRight: 8 }} />{t('storage_clearing')}</>
            ) : t('storage_clear')}
          </button>
        </>
      )}
    </div>
  )
}
