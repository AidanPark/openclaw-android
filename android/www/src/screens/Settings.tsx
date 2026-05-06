import { useRoute } from '../lib/router'
import { t, getLocale, setLocale, availableLocales } from '../i18n'

interface MenuItem {
  icon: string
  label: string
  desc: string
  route: string
  badge?: boolean
}

function getMenu(): MenuItem[] {
  return [
    { icon: '📱', label: t('settings_platforms'), desc: t('settings_platforms_desc'), route: '/settings/platforms' },
    { icon: '🔧', label: t('tools_title'), desc: t('settings_tools_desc'), route: '/settings/tools' },
    { icon: '🔄', label: t('settings_updates'), desc: t('settings_updates_desc'), route: '/settings/updates' },
    { icon: '⚡', label: t('settings_keep_alive'), desc: t('settings_keep_alive_desc'), route: '/settings/keep-alive' },
    { icon: '💾', label: t('settings_storage'), desc: t('settings_storage_desc'), route: '/settings/storage' },
    { icon: '🛠', label: t('settings_advanced'), desc: t('settings_advanced_desc'), route: '/settings/advanced' },
    { icon: 'ℹ️', label: t('settings_about'), desc: t('settings_about_desc'), route: '/settings/about' },
  ]
}

export function Settings() {
  const { navigate } = useRoute()
  const currentLocale = getLocale()

  return (
    <div className="page">
      <div className="page-title" style={{ marginBottom: 20 }}>{t('settings_title')}</div>

      {/* Language selector */}
      <div className="section-title">{t('settings_language')}</div>
      <div className="card">
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, minHeight: 48 }}>
          <span className="card-icon">🌐</span>
          <div className="card-content">
            <div className="card-label">{t('settings_language')}</div>
          </div>
          <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
            {availableLocales.map(loc => (
              <button
                key={loc.code}
                className={`lang-btn${currentLocale === loc.code ? ' active' : ''}`}
                onClick={() => { setLocale(loc.code); window.location.reload() }}
              >
                {loc.label}
              </button>
            ))}
          </div>
        </div>
      </div>

      {/* Settings menu */}
      <div className="section-title">{t('settings_title')}</div>
      {getMenu().map(item => (
        <div
          key={item.route}
          className="card clickable"
          onClick={() => navigate(item.route)}
        >
          <div className="card-row">
            <span className="card-icon">{item.icon}</span>
            <div className="card-content">
              <div className="card-label">{item.label}</div>
              <div className="card-desc">{item.desc}</div>
            </div>
            {item.badge && <span className="card-badge" />}
            <span className="card-chevron">›</span>
          </div>
        </div>
      ))}
    </div>
  )
}
