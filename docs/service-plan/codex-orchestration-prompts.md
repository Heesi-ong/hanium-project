# Codex 오케스트레이션 프롬프트

작성일: 2026-07-02
목적: 클로드는 "무엇을 왜 어떻게 고칠지" 방향만 정하고, 실제 코드 수정은 Codex가 하는 협업 구조를 위한 프롬프트 모음입니다. 클로드는 이 흐름에서 코드를 직접 고치지 않고, 조사와 검증만 담당합니다.

전제: 지금까지 Phase 0(계약 수정) → Phase 1(운영 기반: 설정 분리, MySQL/Flyway, Docker) → Phase 2(비동기 전환, 타임아웃, 동시성 락)까지는 다른 클로드 세션이 코드를 직접 고쳐서 완료했습니다(빌드/테스트 미검증 상태). 다음은 Phase 3(인증/소유권)이며, 이 문서는 Phase 3부터 Codex에게 넘기는 것을 전제로 작성했습니다.

---

## 0. 새 세션 빠른 사용법 (매번 이 순서로 반복)

새 오케스트레이션 사이클을 시작할 때마다 아래 3단계를 순서대로 밟습니다. 어느 프롬프트를 누구에게 주는지는 각 섹션 제목에 **[Claude에게]** / **[Codex에게]** 로 명시해뒀습니다.

| 단계 | 무엇을 | 어디로 | 사용 프롬프트 |
|---|---|---|---|
| 1 | 새 Claude 세션을 열고 현재 상태를 파악시켜 다음 작업(Unit)을 정의시킨다 | **Claude** | [2번] |
| 2 | Claude 출력 중 `===COPY TO CODEX START===` ~ `===COPY TO CODEX END===` 사이만 복사한다 | **Codex** | [2번 출력물 / 3번 템플릿 / 6번 예시] |
| 3 | Codex가 끝낸 뒤 변경 내용(diff, 실행 로그)을 다시 Claude에게 붙여넣어 검증·보고를 받는다 | **Claude** | [7번] |

이 세 프롬프트만 있으면 한 사이클이 끝납니다. 다음 Unit은 다시 1단계부터 반복합니다.

---

## 1. 역할 분리

| 역할 | 하는 일 | 하지 않는 일 |
|---|---|---|
| 클로드 (방향 설계) | 현재 상태 확인, 문제 진단, 작업을 작은 단위(Unit)로 쪼갬, Codex 지시문 작성, Codex 결과물 diff 검증·보고 | 파일을 Edit/Write로 직접 수정 |
| Codex (실행) | 지시문에 명시된 파일만, 명시된 범위 안에서 코드 수정 | 지시문 범위 밖 파일 수정, 임의 리팩터링, 정량 분석 로직 임의 변경 |

---

## 2. 🟦 [Claude에게 전달] 새 세션 킥오프 프롬프트

새 Claude 세션을 열고(이 프로젝트 폴더가 연결된 상태) 아래 블록을 그대로 붙여넣습니다. CLAUDE.md는 프로젝트 설정으로 자동 로드됩니다.

```
너는 /Users/ai/Desktop/hanium project의 방향 설계자다. 이 세션에서는 코드를 직접 수정하지 않는다.

역할:
1. git status, 최근 커밋, docs/service-plan/ 아래 기존 갭 분석 문서와 codex-orchestration-prompts.md를 읽고 현재 상태를 확인한다.
2. 다음으로 진행할 작업을 하나의 Unit(30분~1시간 내 끝낼 수 있는 단일 목적 작업)으로 정의한다.
3. codex-orchestration-prompts.md의 [3. Codex 작업 지시문 템플릿]을 채워서 출력한다.
   반드시 아래 두 마커로 감싸서 출력할 것:
   ===COPY TO CODEX START===
   (채운 템플릿 내용)
   ===COPY TO CODEX END===
   마커 밖에는 왜 이 Unit을 골랐는지 등 사용자에게 보여주는 설명만 쓴다. 마커 안 내용은 Codex 외에는 참고할 필요 없는, Codex 전용 지시문이어야 한다.
4. Edit, Write 도구로 코드를 직접 고치지 않는다. Read, Grep, 읽기 전용 Bash 명령만 사용한다.
5. 사용자가 Codex 작업 결과(diff, 로그)를 붙여넣으면 다음을 검증한다.
   - 지시한 범위 밖 파일을 건드리지 않았는가
   - 정량 분석 점수 계산 로직을 임의로 바꾸지 않았는가
   - 사용자의 기존 변경분을 되돌리지 않았는가
   - "완료 조건"과 "검증 방법"이 실제로 충족되었는가
6. 검증 후 다음 형식으로 보고한다: 확인한 현재 상태 / 발견한 문제 / 수정한 항목 / 남은 위험 / 실행한 검증 / 다음 우선순위.
```

---

## 3. 🟩 [Codex에게 전달되는 내용의 틀] Codex 작업 지시문 템플릿

이 템플릿 자체를 Codex에게 주는 게 아니라, 2번 프롬프트를 받은 Claude가 이 틀을 채워서 `===COPY TO CODEX===` 마커 안에 출력합니다. 그 마커 안 내용만 Codex에게 전달합니다.

```
# 작업: {Unit 제목}

## 배경
{왜 필요한지, 어떤 코드 근거인지 — 파일명:줄번호 포함}

## 목표
{이 Unit이 끝나면 무엇이 달라지는지, 한 문장}

## 수정 대상 파일
- {경로 1} — {무엇을 바꾸는지}
- {경로 2} — {무엇을 바꾸는지}
- (신규 파일이면 "신규"라고 표시)

## 하지 말아야 할 것
- 위 목록에 없는 파일은 건드리지 않는다.
- 정량 분석 점수 계산 로직(analysis-engine 내부)은 바꾸지 않는다.
- 기존 사용자 변경분을 되돌리지 않는다.
- 이 Unit 범위를 벗어나는 리팩터링을 하지 않는다.

## 완료 조건 (Definition of Done)
{구체적 기준. 예: "로그인 없이 /api/results/{jobId} 호출 시 401"}

## 검증 방법
{Codex가 실행해서 확인할 명령. 예: ./gradlew test, npm run build, pytest}
못 돌렸으면 "못 돌렸다"고 명시할 것. "동작할 것이다" 같은 표현 금지.

## 보고 형식
- 수정한 파일 목록과 각각 한 일
- 실행한 검증 명령과 결과(성공/실패/못 돌림)
- 남은 위험, 후속 작업 필요 항목
```

---

## 4. 작업 흐름

1. 클로드가 상태 스캔 → Unit 정의 → Codex 지시문 작성
2. 지시문을 Codex에 전달 → Codex가 코드 수정
3. 클로드가 diff·검증 결과 확인 → G번 보고 형식으로 정리
4. 사용자 승인 후 다음 Unit 진행

---

## 5. Phase 3(인증/소유권) 세부 Unit 로드맵

기존 갭 분석 문서(additional-gap-analysis.md)의 A1/A2/A5를 Phase 3에 포함시켰습니다.

1. **Unit 1 — 사용자 엔티티 + 로그인 인증 골격** (아래에 지시문 예시 작성)
2. **Unit 2 — AnalysisJob 소유자 연결 + 결과 조회/삭제 API 소유권 검증** (jobId만 알면 조회/삭제되는 구조 제거)
3. **Unit 3 — analysis-engine/video-llm-engine 내부 인증(API 키) + 업로드 파일 매직바이트 검증** (A1, A2)
4. **Unit 4 — 사용자별 요청 횟수 제한(rate limiting)** (A5)

---

## 6. 🟩 [Codex에게 전달] 바로 사용 가능한 예시 — Phase 3 Unit 1

아래 마커 사이 내용을 그대로 복사해서 Codex에 붙여넣으면 됩니다.

```
===COPY TO CODEX START===
# 작업: 사용자 엔티티 + 로그인 인증 골격 도입

## 배경
backend에는 현재 User 엔티티도, Spring Security 설정도 없다(backend/src/main/java/com/hanium/presentation/domain 아래 video/result/analysis 세 도메인만 존재, security/auth 관련 파일 0개 확인됨). 결과 조회 API(ResultController.java)는 jobId만 알면 누구나 접근 가능한 상태다. 서비스화 기준 7번("인증, 권한, 결과 소유권 검증")을 충족하려면 인증 골격이 먼저 있어야 한다.

## 목표
이메일/비밀번호로 회원가입·로그인이 가능하고, 로그인한 사용자만 인증된 요청으로 인식되는 최소 골격을 만든다. (이 Unit에서는 아직 결과 소유권 연결은 하지 않는다 — Unit 2에서 진행)

## 수정 대상 파일
- backend/build.gradle — spring-boot-starter-security, jjwt(또는 팀이 선호하는 JWT 라이브러리) 의존성 추가 (신규 의존성)
- backend/src/main/java/com/hanium/presentation/domain/user/entity/User.java — 신규, id/email/passwordHash/createdAt
- backend/src/main/java/com/hanium/presentation/domain/user/repository/UserRepository.java — 신규
- backend/src/main/java/com/hanium/presentation/presentation/controller/AuthController.java — 신규, POST /api/auth/signup, POST /api/auth/login
- backend/src/main/java/com/hanium/presentation/global/config/SecurityConfig.java — 신규, /api/auth/** 는 인증 없이 허용, 나머지 /api/** 는 인증 필요
- backend/src/main/resources/db/migration 아래 신규 Flyway 마이그레이션 파일 — users 테이블 생성 (기존 마이그레이션 파일은 수정하지 말고 새 버전 파일을 추가할 것)
- frontend는 이 Unit에서 건드리지 않는다 (로그인 화면 연결은 별도 Unit)

## 하지 말아야 할 것
- AnalysisJob, ResultController, AnalysisController는 이 Unit에서 건드리지 않는다(Unit 2 범위).
- analysis-engine, video-llm-engine 코드는 건드리지 않는다.
- 기존 Flyway 마이그레이션 파일 내용을 수정하지 않는다(새 버전 파일만 추가).
- 비밀번호는 반드시 해시(BCrypt)로 저장하고 평문으로 저장/로그 출력하지 않는다.

## 완료 조건 (Definition of Done)
- POST /api/auth/signup 으로 이메일/비밀번호 가입이 되고 동일 이메일 재가입은 실패한다.
- POST /api/auth/login 성공 시 인증 토큰(JWT 등)이 반환된다.
- 토큰 없이 보호된 엔드포인트(예: 임시로 하나 지정) 호출 시 401이 반환된다.

## 검증 방법
./gradlew test 실행 결과를 보고할 것. 이 저장소는 과거 세션에서 네트워크 차단/Java 버전 문제로 gradle 빌드가 안 된 적이 있으므로, 실행이 안 되면 "못 돌렸다"고 명시하고 원인을 적을 것.

## 보고 형식
- 수정/신규 파일 목록과 각각 한 일
- 실행한 검증 명령과 결과(성공/실패/못 돌림)
- 남은 위험, 후속 작업 필요 항목
===COPY TO CODEX END===
```

---

## 7. 🟦 [Claude에게 전달] Codex 작업 완료 후 검증 요청 프롬프트

Codex가 작업을 마치면, Codex가 출력한 결과(수정 파일 목록, diff, 실행한 검증 명령과 결과)를 그대로 복사해서 원래 Claude 세션(2번에서 연 세션)에 아래와 함께 붙여넣습니다.

```
Codex가 아래 Unit 작업을 마쳤다. 결과를 검증하고 [2번 프롬프트의 6번 항목] 형식으로 보고해라.
검증 후 문제가 없으면 다음 Unit을 같은 방식(3번 템플릿, COPY TO CODEX 마커)으로 준비해라.

--- Codex 작업 결과 ---
{여기에 Codex의 출력을 그대로 붙여넣기}
```
