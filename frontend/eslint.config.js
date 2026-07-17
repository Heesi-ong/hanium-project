import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'
import { defineConfig, globalIgnores } from 'eslint/config'

export default defineConfig([
  globalIgnores(['dist', 'coverage', 'playwright-report', 'test-results']),
  {
    files: ['**/*.{js,jsx}'],
    extends: [
      js.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    languageOptions: {
      globals: globals.browser,
      parserOptions: { ecmaFeatures: { jsx: true } },
    },
  },
  {
    // E2E 스펙과 설정 파일은 Node 환경에서 실행되므로 node 전역(process 등)을 허용합니다.
    // 브라우저 컨텍스트(page.evaluate 내부)에서 쓰는 window 등도 함께 허용합니다.
    files: ['e2e/**/*.{js,jsx}', '*.config.{js,ts}'],
    languageOptions: {
      globals: { ...globals.node, ...globals.browser },
    },
  },
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      ...tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    languageOptions: {
      globals: globals.browser,
      parserOptions: { ecmaFeatures: { jsx: true } },
    },
    rules: {
      'react-refresh/only-export-components': [
        'error',
        {
          allowExportNames: [
            'BUTTON_BASE_CLASSNAME',
            'BUTTON_VARIANT_CLASSNAMES',
            'buttonVariantClassName',
          ],
        },
      ],
    },
  },
])
