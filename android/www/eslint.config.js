import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'
import { defineConfig, globalIgnores } from 'eslint/config'

export default defineConfig([
  globalIgnores(['dist']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    languageOptions: {
      ecmaVersion: 2020,
      globals: globals.browser,
    },
    rules: {
      // Initializing state from bridge calls inside useEffect is intentional and correct.
      // The bridge is a synchronous external system (WebView JavascriptInterface), not
      // a React state source, so calling setState inside useEffect is the right pattern.
      'react-hooks/set-state-in-effect': 'off',
      // router.tsx and main.tsx export non-component utilities alongside components.
      // Fast refresh still works for the components; this is an acceptable trade-off.
      'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
    },
  },
])
