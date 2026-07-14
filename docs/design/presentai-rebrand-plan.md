# PresentAI 리브랜딩 설계 문서 (0단계)

작성일: 2026-07-14

## 배경과 범위

이 문서는 기존 "AI Presentation Coach"(밝은 웜톤 팔레트, 순수 JS/CSS 스택)를 "PresentAI"
다크 테마 브랜드로 전면 교체하고, TypeScript/Tailwind CSS/GSAP/Framer Motion/Three.js 등
기술 스택을 전체 마이그레이션하기 위한 0단계 설계 문서다. 이 문서 자체는 코드를 변경하지
않으며, 이후 단계(1단계 기술 스택 세팅, 2단계 공통 컴포넌트, 3단계 랜딩페이지, 4단계 기존
화면 리테마, 5단계 신규 기능, 6단계 3D/고급 인터랙션)가 참조하는 기준 자료다.

기존 서비스는 이 문서 작성 시점 기준 83개의 프론트엔드 테스트와 다수의 검증된 페이지/컴포넌트를
가지고 있다. 마이그레이션은 이를 한 번에 깨뜨리지 않는 점진적 방식으로 진행한다(아래
"기술 스택 도입 전략" 참고).

## 디자인 토큰

### 배경/서피스 색상

| 이름 | 값 |
| --- | --- |
| Background Primary | `#090807` |
| Background Secondary | `#100E0C` |
| Surface Primary | `#17130F` |
| Surface Secondary | `#211A14` |
| Elevated Surface | `#2A2018` |

### 메인 컬러 (오렌지 계열)

| 이름 | 값 |
| --- | --- |
| Primary Orange | `#C85A19` |
| Primary Bright | `#F27424` |
| Primary Deep | `#7A2F0B` |
| Highlight Orange | `#FF934D` |
| Soft Orange | `#F6A66B` |

### 텍스트/보더

| 이름 | 값 |
| --- | --- |
| Warm White | `#F7F3EE` |
| Text Primary | `#F5EFE8` |
| Text Secondary | `#B7ADA4` |
| Text Muted | `#776E66` |
| Border Default | `rgba(255,255,255,0.09)` |
| Border Highlight | `rgba(242,116,36,0.45)` |

### 상태 컬러

| 이름 | 값 |
| --- | --- |
| Success | `#4FC78A` |
| Warning | `#F4B04A` |
| Error | `#E7685A` |
| Information | `#72A5FF` |

### 그래디언트

- Primary Gradient: `linear-gradient(135deg, #7A2F0B 0%, #C85A19 45%, #FF934D 100%)`
- Dark Ambient Gradient: `radial-gradient(circle at 50% 30%, rgba(200,90,25,0.25), transparent 55%)`
- Hologram Gradient: `linear-gradient(135deg, rgba(255,147,77,0.85), rgba(242,116,36,0.25), rgba(255,255,255,0.08))`
- Glass Surface: 배경 `rgba(28,22,17,0.72)`, 테두리 `rgba(255,255,255,0.10)`, 블러 `backdrop-filter: blur(20px)`, 그림자 `0 24px 80px rgba(0,0,0,0.35)`

오렌지 컬러는 주요 CTA, 데이터 강조, 활성 상태, 분석선 등에 제한적으로 사용하고 모든
요소에 과도하게 적용하지 않는다.

### 타이포그래피

- 한국어 본문: Pretendard Variable
- 영문 제목: Geist 또는 Inter
- 숫자/점수: Geist Mono 또는 JetBrains Mono

| 스타일 | 데스크톱 | 태블릿 | 모바일 | Weight | Letter spacing |
| --- | --- | --- | --- | --- | --- |
| Display XL | 72–96px | 56–68px | 40–48px | 650–750 | -0.04em |
| Heading 1 | 48–64px | - | - | 650 | -0.035em |
| Heading 2 | 36–48px | - | - | 620 | - |
| Heading 3 | 24–32px | - | - | 600 | - |
| Body Large | 18–21px (line-height 1.65) | - | - | - | - |
| Body | 15–17px (line-height 1.65) | - | - | - | - |
| Caption | 12–14px (line-height 1.5) | - | - | - | - |

### 레이아웃/스페이싱

- 콘텐츠 최대 너비: 1280px, 대형 시각화 영역: 1440px, 본문 텍스트 최대 너비: 720px
- 그리드: 데스크톱 12열 / 태블릿 8열 / 모바일 4열
- 좌우 여백: 데스크톱 72–96px / 태블릿 32–48px / 모바일 20–24px
- 섹션 간격: 데스크톱 160–240px / 태블릿 120–160px / 모바일 88–120px
- 모서리 반경: 소형 10–14px / 카드 18–24px / 대형 패널 28–36px / 알약형 버튼 999px

### 모션 타이밍

- 일반 UI 전환: 180–260ms
- 카드 등장: 400–650ms
- 섹션 전환: 700–1,000ms
- Hero 연출: 1,200–2,000ms
- Easing: cubic-bezier 기반 부드러운 감속
- `prefers-reduced-motion`을 반드시 지원해야 한다(기존 `App.css`에 이미 전역
  `@media (prefers-reduced-motion: reduce)` 규칙이 있으므로, 새 다크 테마 스타일도
  이 규칙의 적용을 받도록 유지한다).

## 기술 스택 도입 전략

기존 코드베이스(`frontend/package.json` 기준)는 TypeScript, Tailwind CSS, shadcn/ui,
GSAP, Framer Motion, Three.js/React Three Fiber/Drei, Zustand, TanStack Query,
React Hook Form/Zod가 전혀 없는 순수 JS + 플레인 CSS + React Context 구조다. 아래
원칙으로 점진 도입한다.

1. **TypeScript**: 기존 `.jsx` 파일을 일괄 `.tsx`로 전환하지 않는다. `tsconfig.json`에
   `allowJs: true`를 설정해 `.jsx`와 `.tsx`가 공존하도록 하고, 1단계 이후 새로 작성하는
   파일부터 `.tsx`로 작성한다. 기존 83개 테스트와 기존 페이지/컴포넌트는 당장 손대지 않는다.
2. **Tailwind CSS**: 기존 페이지별 `.css` 파일(`App.css`, `HomePage.css` 등)을 즉시
   삭제하지 않는다. Tailwind를 설치해 새로 작성하는 컴포넌트부터 유틸리티 클래스를 쓰고,
   기존 화면은 4단계(기존 서비스 화면 리테마)에서 순차적으로 옮긴다.
3. **애니메이션 라이브러리(GSAP, Framer Motion)**: 같은 요소에 두 라이브러리를 중복
   적용하지 않는다. 스크롤 기반 장면 전환/복합 애니메이션은 GSAP, 컴포넌트 전환과
   마이크로 인터랙션은 Framer Motion으로 역할을 나눈다.
4. **3D(Three.js/R3F/Drei)**: Hero와 AI 코치 화면에 한정한다. 나머지 화면에는 도입하지
   않는다. 모바일에서는 경량화된 영상/이미지/Canvas 대체 UI를 제공한다.
5. **상태관리(Zustand)/서버상태(TanStack Query)**: 기존 React Context(`AuthContext`,
   `ToastContext`, `ConfirmContext`)를 즉시 교체하지 않는다. 신규 기능(5단계의 온보딩,
   AI 코치 대화형 페이지 등)부터 새 방식으로 작성하고, 기존 Context는 필요할 때만 점진
   전환한다.

## 신규 기능 범위 메모

- **온보딩**: 신규 API/DB 필요(사용자 목적·경험 수준·개선 목표 저장). 5단계에서 별도 설계.
- **AI 코치 대화형 페이지**: 기존 OpenAI 피드백(1회성 텍스트)과 달리 대화 이력 저장이
  필요한 신규 기능이다. 5단계에서 별도 설계.
- **요금제(Pricing)**: 실제 결제 연동 여부가 결정되지 않았다. 결제를 붙이지 않는다면
  백엔드 변경 없이 "요금 정책 준비 중" 플레이스홀더 페이지만으로 충분하다(원본 프롬프트
  22번 항목도 동일하게 안내한다). 결제 연동 여부는 이 문서 이후 별도로 확인한다.
- **관리자 대시보드**: 현재 백엔드에 사용자 role/권한 체계가 없다. 신설이 필요하며,
  일반 사용자에게 관리자 기능이 노출되지 않도록 인가 설계가 선행되어야 한다. 5단계에서
  별도 설계.

## 다음 단계

1단계(기술 스택 기반 세팅)부터는 이 문서의 디자인 토큰 값과 도입 전략을 그대로 따른다.
1단계는 화면 변화가 거의 없어야 하며, 빌드와 기존 테스트가 계속 통과하는 것이 완료 기준이다.
