import { createContext, useContext } from 'react'
import { en } from './en'
import { es } from './es'

export type TranslationKey = keyof typeof en
type Translations = Record<TranslationKey, string>

const locales: Record<string, Translations> = { en, es }

function detectLocale(): string {
  try {
    const saved = localStorage.getItem('locale')
    if (saved && locales[saved]) return saved
  } catch { /* ignore */ }

  const lang = navigator.language || ''
  if (lang.startsWith('es')) return 'es'
  return 'en'
}

let currentLocale = detectLocale()
let currentTranslations = locales[currentLocale] || en

export function getLocale(): string { return currentLocale }

export function setLocale(locale: string) {
  if (locales[locale]) {
    currentLocale = locale
    currentTranslations = locales[locale]
    try { localStorage.setItem('locale', locale) } catch { /* ignore */ }
  }
}

export function t(key: TranslationKey, vars?: Record<string, string>): string {
  let text = currentTranslations[key] || en[key] || key
  if (vars) {
    for (const [k, v] of Object.entries(vars)) {
      text = text.replace(`{${k}}`, v)
    }
  }
  return text
}

export const LocaleContext = createContext<string>(currentLocale)
export const useLocale = () => useContext(LocaleContext)

export const availableLocales = [
  { code: 'en', label: 'English' },
  { code: 'es', label: 'Español' },
]
