# 추천 기능 조합 구현 기록 (2026-08-17)

## 범위

학생 프로젝트의 로컬/통제된 테스트 시연에서 다음 흐름을 더 이해하기 쉽고 재현 가능하게 만드는 작업이다.

`영상 업로드 → 비동기 분석 → 분석 품질 확인 → 맞춤 피드백 → 목표 재연습 → 기준 대비 비교`

공개 배포, production 운영 관제, 결제, 외부 서비스 승격은 포함하지 않는다. 정량 점수 산식도 변경하지 않았다.

## 구현한 기능 조합

### 1. 분석 실행 신뢰성

- analysis-engine의 HTTP 응답이 성공이어도 본문 `status`가 `success`가 아니면 백엔드 job을 실패 처리한다.
- 완료·실패·취소가 경합할 때 먼저 확정된 terminal 상태를 나중 후처리가 덮어쓰지 않는다.
- 프론트 상태 폴링은 이전 요청이 끝난 뒤 다음 요청을 예약해 느린 응답 중 중복 요청이 쌓이지 않는다.
- 로그에는 기존 `STAGE_TRANSITION`, jobId MDC와 함께 logical failure와 superseded 종료가 구분되어 남는다.

### 2. 점수 근거와 분석 품질

- 최종 결과에 타입화된 `analysisQuality`를 추가했다.
- 제공 필드: 자세/얼굴 검출률, 낮은 신뢰도 여부, 음성 분석 방식, STT fallback 여부, 신뢰도 감점, 감점 사유, 점수 산식 버전.
- 상세 화면에서 감점 근거와 카메라 구도·조명·음질·영상 길이 재촬영 가이드를 표시한다.
- 과거 결과처럼 메타데이터가 없으면 0으로 표시하지 않고 `품질 정보 없음`으로 구분한다.

### 3. 온보딩 기반 개인화

- 온보딩의 발표 목적, 경험 수준, 개선 목표를 OpenAI 최종 피드백과 결과별 AI 코치의 비식별 컨텍스트로 전달한다.
- 이메일, 이름 등 식별 정보는 프로필 DTO에 포함하지 않는다.
- 코칭 프로필은 조언의 난이도와 우선순위에만 사용하며 정량 점수와 관찰 사실을 바꾸지 않도록 프롬프트에 명시했다.
- OpenAI/Video LLM이 꺼져 있거나 fallback인 경우의 기존 표시와 동작은 유지한다.

### 4. 목표 재연습과 자동 비교

- 완료된 자기 결과에서 자세·시선·음성·제스처·표정 중 하나를 연습 목표로 선택할 수 있다.
- 새 업로드는 `baselineJobId`와 제한된 `practiceGoal`을 multipart 필드로 함께 보낸다.
- 백엔드는 기준 job 존재 여부, 소유권, 완료 상태를 다시 검증한 뒤 `analysis_jobs`에 저장한다.
- 새 결과 상세에서 선택한 항목의 기준 점수, 현재 점수, 증감 값을 자동 표시한다.
- Flyway `V27__add_practice_baseline_to_analysis_jobs.sql`이 nullable 기준 job ID와 목표 필드를 추가한다.

## 로컬 시연 순서

1. 로그인 후 온보딩에서 발표 목적·경험·개선 목표를 저장한다.
2. `/upload`에서 영상을 업로드하고 분석을 완료한다.
3. 결과 상세의 `점수 근거와 분석 품질`에서 검출률과 감점 사유를 확인한다.
4. `목표 재연습`에서 한 항목을 선택한다.
5. 이동한 업로드 화면에서 집중 재연습 배너와 기준 jobId를 확인하고 새 영상을 분석한다.
6. 새 결과의 `목표 재연습`에서 기준 대비 점수 변화를 확인한다.
7. AI 코치에게 우선 연습 항목을 질문해 온보딩 목표를 반영한 답변을 확인한다. 실제 외부 호출 여부는 생성 방식 배지와 로그로 구분한다.

## 데이터와 보안 규칙

- 기준 결과는 같은 사용자 소유이며 `COMPLETED`일 때만 연결할 수 있다.
- 기준 결과를 읽을 때도 기존 결과 소유권 검증을 그대로 거친다.
- 점수 비교는 저장된 두 결과의 동일 점수 필드만 사용한다.
- 외부 AI 전송 동의와 `useOpenAi` 옵션을 우회하지 않는다.
- 기준 결과가 삭제되더라도 재연습 결과 자체를 보존할 수 있도록 DB 외래키 대신 nullable 논리 jobId를 사용한다. 이 경우 화면은 기준 점수를 `-`로 표시한다.

## 검증 기준

- 백엔드: 전체 Gradle 테스트와 JaCoCo 검증
- 프론트: 전체 Vitest, ESLint, Vite build
- Python: analysis-engine와 video-llm-engine 전체 pytest
- 스키마: H2 기반 Spring repository 테스트와 가능할 경우 실제 로컬 MySQL/Flyway 검증
- 실제 업로드→분석→결과 E2E는 네 서비스가 기동된 로컬 환경에서 별도로 실행하며, 실행하지 않은 경우 완료로 보고하지 않는다.

## 이번 구현의 검증 결과

- 백엔드 `./gradlew clean test --rerun-tasks --no-daemon`: 558개 실행, 실패 0, 조건부 skip 9, JaCoCo 통과.
- Testcontainers 실제 인프라: MySQL 8.4 Flyway와 Redis 7 인증/TTL 2개 통과.
- 기존 로컬 MySQL: Flyway 26→27 적용 성공, `baseline_job_id`, `practice_goal` 컬럼 직접 확인.
- 프론트 전체 Vitest: 53개 파일, 307개 테스트 통과.
- 프론트 ESLint와 Vite build 통과.
- analysis-engine pytest: 171개 통과, 커버리지 87.46%.
- video-llm-engine pytest: 213개 통과(1개 조건부 deselect), 커버리지 94.10%. 최초 샌드박스 포트 바인딩 제한은 권한 허용 후 재실행해 해소했다.
- 로컬 Docker Compose 이미지를 재빌드한 뒤 Playwright 실제 파이프라인 E2E 통과: 업로드, 큐/worker 분석, 새로고침 복구, 결과 조회, `analysisQuality` API, 품질/재연습 UI, 영상 접근 토큰, 테스트 계정 정리까지 확인.
- E2E 최종 점수: golden 48, actual 48, drift 0, 허용 오차 3.
- worker 로그에서 `BASIC_ANALYSIS(10%) → COMPACT_ANALYSIS(60%) → RESULT_MERGE(90%) → COMPLETED(100%)`와 동일 jobId MDC를 확인했다.
