import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.js'],
    // 단위 테스트는 src/ 안의 *.test.* 만 대상으로 합니다. e2e/*.spec.js(Playwright)는
    // vitest가 아니라 `npm run test:e2e`로 실행되므로 여기서 제외합니다.
    include: ['src/**/*.{test,spec}.{js,jsx,ts,tsx}'],
    exclude: ['e2e/**', 'node_modules/**', 'dist/**'],
    // `npm run test:coverage`로 커버리지 리포트 생성(@vitest/coverage-v8 필요).
    // e2e 디렉터리와 설정/진입 파일은 단위 커버리지 대상에서 제외합니다.
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html', 'lcov'],
      reportsDirectory: './coverage',
      exclude: [
        'e2e/**',
        'dist/**',
        '**/*.config.*',
        'src/test/**',
        'src/main.jsx',
        '**/*.test.{js,jsx,ts,tsx}',
      ],
    },
  },
})
