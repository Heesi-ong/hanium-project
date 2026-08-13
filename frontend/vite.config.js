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
      // coverage는 측정만 하고 최소 하락 기준이 없었다(2026-08-03 서비스화 점검 P2-06).
      // 현재 실측치(2026-08-13 기준 statements 78.33%, branches 71.41%, functions
      // 80.55%, lines 79.04%)보다 낮은 70%를 공통 바닥선으로 두어 `npm run
      // test:coverage`(CI)에서 실질적인 회귀를 잡아낸다.
      thresholds: {
        statements: 70,
        branches: 70,
        functions: 70,
        lines: 70,
      },
    },
  },
})
