import { useState, useEffect } from 'react'
import { useRoute } from '../lib/router'
import { bridge } from '../lib/bridge'
import { t } from '../i18n'

interface StorageInfo {
  totalBytes: number
  freeBytes: number
  bootstrapBytes: number
  wwwBytes: number
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

function StorageRow({
  label, bytes, total, color, desc,
}: {
  label: string; bytes: number; total: number; color: string; desc?: string
}) {
  return (
    <div className="card" style={{ marginBottom: 8 }}>
      <div className="card-row" style={{ cursor: 'default', marginBottom: 8 }}>
        <div className="card-content">
          <div className="card-label">{label}</div>
          {desc && <div className="card-desc">{desc}</div>}
        </div>
        <div style={{ textAlign: 'right', flexShrink: 0 }}>
          <div style={{ fontSize: 14, fontWeight: 700, fontFamily: 'monospace' }}>
            {formatBytes(bytes)}
          </div>
          <div style={{ fontSize: 11, color: 'var(--text-muted)', fontFamily: 'monospace' }}>
            {pct(bytes, total)}
          </div>
        </div>
      </div>
      <div className="storage-bar">
        <div className="storage-fill" style={{
          width: `${Math.min(100, total > 0 ? (bytes / total) * 100 : 0)}%`,
          background: color,
        }} />
      </div>
    </div>
  )
}

export function SettingsStorage() {
  const { navigate } = useRoute()
  const [info, setInfo] = useState<StorageInfo | null>(null)
  const [source, setSource] = useState<string>('bootstrap')
  const [clearing, setClearing] = useState(false)
  const [error, setError] = useState<string | null>(null)

  function loadInfo() {
    setError(null)
    try {
      const data = bridge.callJson<StorageInfo>('getStorageInfo')
      if (data && typeof data === 'object') {
        // Normalize: ensure all fields are numbers
        const normalized: StorageInfo = {
          totalBytes: Number(data.totalBytes) || 0,
          freeBytes: Number(data.freeBytes) || 0,
          bootstrapBytes: Number(data.bootstrapBytes) || 0,
          wwwBytes: Number(data.wwwBytes) || 0,
          payloadBytes: Number(data.payloadBytes) || 0,
          cacheBytes: Number(data.cacheBytes) || 0,
          nodeBytes: Number(data.nodeBytes) || 0,
          openclawBytes: Number(data.openclawBytes) || 0,
        }
        setInfo(normalized)
      } else {
        setError('No se pudo obtener información de almacenamiento')
      }
    } catch (e) {
      setError(`Error: ${e}`)
    }

    try {
      const bs = bridge.callJson<{ source?: string }>('getBootstrapStatus')
      if (bs?.source) setSource(bs.source)
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

  // Compute totals
  const usedBytes = info
    ? (info.bootstrapBytes || 0) + (info.wwwBytes || 0) +
    (info.payloadBytes || 0) + (info.cacheBytes || 0)
    : 0
  const diskTotal = info?.totalBytes || 1

  const segments: BarSegment[] = info ? [
    { label: source === 'payload' ? 'Payload' : 'Bootstrap', bytes: info.bootstrapBytes || 0, color: '#58a6ff' },
    { label: 'Web UI', bytes: info.wwwBytes || 0, color: '#3fb950' },
    { label: 'Payload', bytes: info.payloadBytes || 0, color: '#d29922' },
    { label: 'Caché', bytes: info.cacheBytes || 0, color: '#8b949e' },
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
          {/* Resumen total con barra combinada */}
          <div className="card" style={{ marginBottom: 16 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 12 }}>
              <div style={{ fontSize: 13, color: 'var(--text-secondary)' }}>
                {t('storage_total')}
              </div>
              <div style={{ fontSize: 20, fontWeight: 800, fontFamily: 'monospace' }}>
                {formatBytes(usedBytes)}
              </div>
            </div>
            <StorageBar segments={segments} total={diskTotal} />
          </div>

          {/* Bootstrap / Payload */}
          <StorageRow
            label={source === 'payload' ? 'Payload (glibc + node)' : t('storage_bootstrap')}
            bytes={info.bootstrapBytes || 0}
            total={diskTotal}
            color="#58a6ff"
            desc={source === 'payload' ? 'Entorno de ejecución glibc' : 'Entorno Termux embebido'}
          />

          {/* Payload adicional si existe */}
          {(info.payloadBytes || 0) > 0 && (
            <StorageRow
              label="Payload extraído"
              bytes={info.payloadBytes || 0}
              total={diskTotal}
              color="#d29922"
              desc="payload-final.tar.gz extraído"
            />
          )}

          {/* Node.js si se reporta por separado */}
          {(info.nodeBytes || 0) > 0 && (
            <StorageRow
              label="Node.js"
              bytes={info.nodeBytes || 0}
              total={diskTotal}
              color="#79c0ff"
              desc="node.real + módulos npm"
            />
          )}

          {/* OpenClaw si se reporta por separado */}
          {(info.openclawBytes || 0) > 0 && (
            <StorageRow
              label="OpenClaw"
              bytes={info.openclawBytes || 0}
              total={diskTotal}
              color="#56d364"
              desc="openclaw.mjs + node_modules"
            />
          )}

          {/* Web UI */}
          <StorageRow
            label={t('storage_www')}
            bytes={info.wwwBytes || 0}
            total={diskTotal}
            color="#3fb950"
            desc="Interfaz React"
          />

          {/* Caché */}
          {(info.cacheBytes || 0) > 0 && (
            <StorageRow
              label="Caché"
              bytes={info.cacheBytes || 0}
              total={diskTotal}
              color="#8b949e"
              desc="Archivos temporales"
            />
          )}

          {/* Espacio libre */}
          <div className="card" style={{ marginBottom: 8 }}>
            <div className="card-row" style={{ cursor: 'default' }}>
              <div className="card-content">
                <div className="card-label">{t('storage_free')}</div>
                <div className="card-desc">Disponible en el dispositivo</div>
              </div>
              <div style={{ textAlign: 'right' }}>
                <div style={{ fontSize: 14, fontWeight: 700, fontFamily: 'monospace' }}>
                  {formatBytes(info.freeBytes || 0)}
                </div>
                <span className="pill pill-success" style={{ fontSize: 11 }}>
                  {pct(info.freeBytes || 0, diskTotal)}
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
