# 추가 심층 조사: 문제점 및 개선 방향

작성일: 2026-07-02
범위: 1차 갭 분석(구조/CI/DB/비동기/인증/OpenAI/테스트) 이후, 실제 서비스 운영 시 사고로 이어질 수 있는 **보안**과 **안정성/동시성/확장성** 항목을 코드 기준으로 추가 조사한 결과입니다. 코드를 읽고 근거를 확인만 했고, 아직 아무 코드도 수정하지 않았습니다.

용어 설명이 필요한 것은 처음 등장할 때 괄호로 풀어서 적었습니다.

---

## A. 보안 / 데이터 보호

### A1. analysis-engine이 임의 파일을 읽을 수 있음 (긴급, Critical)
- **문제**: `analysis-engine/app/api/basic_analysis.py`의 분석 요청은 `videoPath`라는 문자열을 그대로 받아서 그 경로의 파일을 엽니다. 이 API 자체에는 로그인 검사도, "백엔드가 보낸 요청인지" 확인하는 절차도 없습니다. 즉 8001 포트에 접근할 수 있는 사람은 누구든 서버 안의 임의 파일 경로를 넣어서 읽게 만들 수 있습니다 (일종의 "로컬 파일 읽기" 취약점).
- **왜 위험한가**: 지금은 로컬에서만 실행하니 문제가 안 보이지만, 배포 후 analysis-engine 포트가 외부에서 접근 가능하게 열리면 서버 내부 파일이 유출될 수 있습니다.
- **개선 방향**: (1) analysis-engine/video-llm-engine에 "내부 서비스 전용 API 키" 같은 최소한의 인증을 걸어 backend만 호출할 수 있게 막기, (2) 두 엔진을 외부 인터넷에서 직접 접근 불가능한 내부망으로만 열기(Docker 내부 네트워크, 방화벽), (3) `videoPath`를 그대로 받지 말고 backend가 발급한 jobId만 받아서 정해진 규칙으로 경로를 서버가 직접 계산하도록 변경.

### A2. 업로드 파일의 내용물 검증이 없음 (High)
- **문제**: `VideoFileCommandService.java`는 확장자(.mp4 등)만 확인합니다. 실제 파일 내용이 영상인지 확인하는 절차("매직바이트 검사" — 파일 맨 앞 몇 바이트를 보고 진짜 파일 형식을 판별하는 방법)가 없습니다. 확장자만 `.mp4`로 바꾼 아무 파일도 그대로 ffmpeg/OpenCV/MediaPipe에 전달됩니다.
- **왜 위험한가**: 악의적으로 조작된 파일이 분석 엔진에 그대로 들어가면 예상치 못한 크래시나 리소스 남용으로 이어질 수 있습니다. 바이러스 검사도 없습니다.
- **개선 방향**: 업로드 직후 실제 파일 형식을 검사(예: ffprobe로 컨테이너/코덱 확인)하고 실패하면 즉시 거부. 장기적으로 바이러스 스캔(ClamAV 등) 도입 검토.

### A3. 서비스 간 호출에 타임아웃이 없어 인증 부재와 함께 위험이 커짐 (High, 안정성과 겹침)
- A1과 연결되는 문제로, backend가 analysis-engine을 호출하는 `RestClientConfig.java`에 타임아웃 설정 자체가 없습니다. 인증도 없고 타임아웃도 없다는 것은, 문제가 생겼을 때 막을 방법이 이중으로 없다는 뜻입니다. (자세한 내용은 B3 참고)

### A4. 입력 값 검증(Validation)이 사실상 꺼져 있음 (Medium)
- **문제**: `spring-boot-starter-validation` 라이브러리는 추가되어 있지만 실제로 쓰이는 곳이 한 곳도 없습니다(`@Valid` 사용 0건). `AnalysisRunRequest` 같은 요청 객체에 아무 제약도 없어서, 이상한 값이 들어와도 컨트롤러 단계에서 걸러지지 않습니다.
- **개선 방향**: 요청 DTO에 `@NotNull`, `@Pattern` 등 최소 제약 추가하고 컨트롤러에 `@Valid` 적용. 이미 라이브러리는 있으니 비용이 크지 않은 작업입니다.

### A5. 남용 방지 장치(Rate Limiting)가 전혀 없음 (Medium)
- **문제**: 영상 업로드/분석은 CPU를 많이 쓰고 OpenAI 비용도 발생하는 "비싼" 작업인데, 같은 사용자가 짧은 시간에 반복 요청하는 것을 막는 장치가 없습니다.
- **개선 방향**: 사용자별/IP별 요청 횟수 제한(예: Bucket4j 라이브러리, 또는 간단히는 "직전 작업이 끝나기 전 새 업로드 금지" 규칙)을 로그인 기능 도입과 함께 추가.

### A6. CORS 설정이 로컬 주소로 하드코딩됨 (Low, 운영 편의성 문제)
- **문제**: `CorsConfig.java`에 허용 주소가 `localhost:5173` 등으로 코드에 직접 박혀 있습니다. 와일드카드(전체 허용)는 아니라서 심각한 보안 문제는 아니지만, 실제 배포 도메인이 생기면 코드를 고치고 다시 빌드해야 합니다.
- **개선 방향**: 허용 주소를 환경변수(`CORS_ALLOWED_ORIGINS`)로 빼서 배포 환경마다 재빌드 없이 설정 가능하게 변경.

### A7. 비밀값이 들어갈 수 있는 설정 파일이 그대로 git에 커밋됨 (Low, 관행 문제)
- **문제**: `application-local.yml`, `application-dev.yml`, `application-prod.yml` 모두 git에 포함되어 있습니다. 지금은 더미 값(`dummy`)뿐이라 실제 유출은 없지만, `.gitignore`로 막혀 있지 않기 때문에 나중에 누군가 실수로 진짜 키를 넣고 커밋하면 그대로 깃 이력에 영구히 남습니다.
- **개선 방향**: 운영 프로파일 설정에는 값 대신 `${OPENAI_API_KEY}` 형태의 환경변수 참조만 남기고, 실제 값이 들어갈 수 있는 파일(`application-secret.yml` 등)은 `.gitignore`에 추가.

---

## B. 안정성 / 동시성 / 확장성

### B1. 무거운 AI 모델을 요청마다 새로 불러옴 (긴급, Critical)
- **문제**: `analysis-engine`에서 음성 인식 모델(faster-whisper)과 자세/얼굴 인식 모델(MediaPipe)이 **매 분석 요청마다 새로 로딩**됩니다 (`basic_analysis.py` 503, 605, 951번 줄). 서버 시작할 때 한 번만 불러와서 재사용하는 방식(흔히 "싱글톤 패턴"이라 부름)이 아닙니다.
- **왜 위험한가**: 요청 1건마다 모델 로딩에만 수 초~수십 초가 낭비되고, 메모리도 매번 새로 할당됩니다. 동시에 여러 명이 업로드하면 서버가 매우 느려지거나 메모리 부족으로 죽을 수 있습니다.
- **개선 방향**: 모델을 앱 시작 시 한 번만 로딩해서 전역으로 재사용하도록 리팩터링. 이번 작업의 우선순위 상위 항목으로 두는 것을 권장합니다.

### B2. 동시 처리량 제한이 전혀 없음 (High)
- **문제**: 분석 작업 개수를 제한하는 장치(대기열, 동시 실행 개수 제한 등)가 없습니다. 서버 스레드 설정도 Spring 기본값(최대 200개) 그대로입니다.
- **왜 위험한가**: 여러 명이 동시에 영상을 올리면 각각 무거운 영상 분석이 동시에 CPU를 다 써버려 전체 서비스가 느려지거나 멈출 수 있습니다.
- **개선 방향**: 1차 보고서에서 제안한 "비동기 작업 큐" 도입 시, 동시에 실행되는 작업 수를 워커 개수만큼으로 자연스럽게 제한하는 구조로 설계.

### B3. 백엔드↔분석엔진 호출에 타임아웃이 없음 (High)
- **문제**: backend가 analysis-engine/video-llm-engine을 호출하는 HTTP 클라이언트(`RestClientConfig.java`)에 연결/응답 시간 제한이 설정되어 있지 않습니다.
- **왜 위험한가**: 분석 엔진이 멈추거나 아주 느려지면, 그걸 호출한 backend 스레드도 같이 무한정 멈춰버립니다. 이런 스레드가 쌓이면 서버 전체가 새로운 요청을 못 받게 됩니다.
- **개선 방향**: 최소한 연결 타임아웃(예: 5초), 응답 타임아웃(영상 길이에 따라 조정 가능한 값, 예: 5~10분)을 명시적으로 설정.

### B4. 로컬 파일 저장 + 인메모리 DB는 서버를 여러 대로 늘릴 수 없는 구조 (High, 장기 이슈)
- **문제**: 업로드 영상/결과 파일이 로컬 디스크에 저장되고, DB는 H2 인메모리(local 프로파일 기준)입니다. dev/prod 설정 파일은 비어 있어 아직 실제 외부 DB 연결도 안 되어 있습니다.
- **왜 위험한가**: 서버를 2대 이상으로 늘려서 트래픽을 분산하려고 하면, A 서버가 저장한 파일을 B 서버가 못 찾는 문제가 생깁니다. 지금 구조로는 "서버 한 대"를 벗어날 수 없습니다.
- **개선 방향**: 이미 계획된 대로 dev/prod에 실제 DB(MySQL 등) 연결 + 파일 저장을 공유 스토리지(S3 호환 오브젝트 스토리지 등)로 옮기는 작업이 필요. 초기 서비스 규모에서는 급하지 않을 수 있으나, 사용자가 늘어나기 전에 구조를 잡아두는 게 좋습니다.

### B5. 결과 목록 조회에 페이지네이션이 없고 N+1 쿼리 발생 (Medium)
- **문제**: `GET /api/results`는 전체 작업을 한 번에 다 가져오며, 각 작업마다 관련 데이터를 하나씩 추가로 조회합니다("N+1 쿼리 문제" — 목록 1번 조회 후 항목 개수만큼 추가 조회가 발생해 항목이 많아질수록 느려지는 흔한 성능 문제).
- **개선 방향**: 목록 API에 페이지 단위 조회(`page`, `size` 파라미터) 추가, 연관 데이터는 한 번에 묶어서 가져오는 방식으로 변경.

### B6. 같은 작업(jobId)에 대한 동시 재실행을 막는 락이 없음 (Medium)
- **문제**: `/run`과 `/retry`를 거의 동시에 두 번 호출하면, 상태 체크와 실제 실행 사이에 틈이 있어 같은 작업이 중복 실행될 수 있습니다(이런 종류의 버그를 "TOCTOU"라고 부르는데, "확인한 시점"과 "실제 사용하는 시점" 사이에 상태가 바뀌어 생기는 문제입니다).
- **개선 방향**: `AnalysisJob`에 버전 필드를 추가해 동시 수정 시 하나만 성공하게 만드는 방식(낙관적 락) 적용, 또는 작업 시작 시 상태를 원자적으로 바꾸는 방식으로 보완.

### B7. 프론트엔드에 화면 전체가 깨지는 것을 막는 안전장치가 없음 (Low)
- **문제**: React의 `ErrorBoundary`(컴포넌트 렌더링 중 오류가 나도 전체 화면이 하얗게 깨지지 않도록 막아주는 안전장치)가 코드베이스에 없습니다. API 실패 자체는 각 페이지에서 잘 처리하고 있어 급한 문제는 아니지만, 예상 밖의 데이터 형식이 오면 화면이 통째로 깨질 수 있습니다.
- **개선 방향**: 최상위 컴포넌트에 ErrorBoundary 하나 추가 (구현 비용이 낮고 효과는 확실한 항목).

---

## C. 기존 Phase 계획에 반영 제안

1차 보고서에서 제안한 Phase 0~6에 아래와 같이 끼워 넣는 것을 권장합니다.

- **Phase 0(당장 고치기)에 추가**: A4(입력 검증 활성화), B7(ErrorBoundary) — 비용 낮고 즉시 적용 가능
- **Phase 1(운영 기반)에 추가**: A6(CORS 환경변수화), A7(설정 파일 시크릿 분리)
- **Phase 2(비동기 처리)와 함께 처리**: B1(모델 프리로딩), B2(동시 처리량 제한), B3(호출 타임아웃), B6(동시 실행 락) — 비동기 큐 구조를 설계할 때 이 네 가지를 같이 해결하는 것이 효율적입니다.
- **Phase 3(보안/사용자 모델)에 추가**: A1(서비스 간 인증), A2(업로드 내용 검증), A5(rate limiting)
- **Phase 6(테스트/운영) 이전에 별도 확인 필요**: B4(수평 확장) — 초기 사용자 규모에 따라 우선순위를 조정할 항목

---

## D. 참고: 의존성 버전 관련 의문점

`analysis-engine/requirements.txt`와 `frontend/package.json`에 명시된 일부 버전(`fastapi==0.138.2`, `pydantic==2.13.4`, `uvicorn==0.49.0`, `vite: ^8.1.0`, `react: ^19.2.7`)이 알려진 실제 릴리스 흐름과 맞지 않아 보입니다. 인터넷 조회 없이 위조 여부를 단정할 수는 없지만, 실제로 설치되는 패키지가 맞는지, 사설/미러 레지스트리를 쓰고 있는 것은 아닌지 한 번 확인해보시는 것을 권장합니다.

---

## 업데이트: 2026-07-03 현재 상태

이 섹션은 2026-07-02 본문을 삭제하지 않고 남겨둔 상태에서, 2026-07-03 기준 실제 코드 재확인 결과를 덧붙인 최신 상태입니다.

### A1. analysis-engine / video-llm-engine 직접 호출 인증 없음

- **판정: 해결**
- `analysis-engine/app/core/security.py:9-29`, `video-llm-engine/app/core/security.py:9-29`에서 `INTERNAL_ENGINE_API_KEY`와 `X-Internal-Api-Key`를 비교하며, 키가 설정되지 않으면 fail-closed로 401을 반환합니다.
- `analysis-engine/app/api/basic_analysis.py:20-24`, `video-llm-engine/app/api/video_llm_analysis.py:11-15`에서 `/api/**` 라우터에 `Depends(verify_internal_api_key)`가 걸려 있습니다. `/health`는 각 `main.py`에 남아 있어 공개 상태입니다.
- backend 호출부도 `AnalysisEngineClient.java:53-58`, `VideoLlmEngineClient.java:53-58`에서 `X-Internal-Api-Key` 헤더를 붙입니다.
- 단, 이는 애플리케이션 레벨의 공유 키 인증입니다. 네트워크 레벨 방화벽/보안그룹으로 8001/8002 포트를 외부에 닫는 운영 설정은 별도 확인이 필요합니다.

### A2. 업로드 파일 내용 검증 없음

- **판정: 부분해결**
- `VideoFileCommandService.java:72-82`에서 확장자 검증 뒤 `validateFileSignature()`를 호출하고, `VideoFileCommandService.java:96-104`에서 `MultipartFile.getBytes()`가 아니라 스트림으로 앞부분만 읽습니다.
- `VideoSignatureValidator.java:7-17`에서 MP4/MOV(`ftyp`), AVI(`RIFF`/`AVI `), MKV(EBML magic)를 검사합니다.
- 확장자 위조 방지는 해결됐지만, 재생 가능한 정상 영상인지, 코덱이 안전한지, 악성 페이로드가 섞였는지까지 검증하는 `ffprobe`/백신/샌드박스 검사는 아직 없습니다.

### A3. 서비스 간 HTTP 호출 타임아웃 없음

- **판정: 해결**
- `RestClientConfig.java:17-21`에 connect timeout 5초, read timeout 10분이 명시됐고, `RestClientConfig.java:31-39`에서 공통 `RestClient.Builder`에 적용됩니다.
- 영상 분석 특성상 read timeout은 길게 잡혀 있지만, 무한 대기는 더 이상 발생하지 않습니다.

### A4. 입력 검증 미흡

- **판정: 부분해결**
- 인증 요청은 `AuthController.java:38-40`, `AuthController.java:90-98`에서 `@Valid`, `@Email`, `@NotBlank`, `@Size`로 검증합니다.
- 분석/결과 jobId 경로는 `AnalysisController.java:19-27`, `AnalysisController.java:72-91`, `ResultController.java:61-80`에서 `@Validated`와 `@Pattern`으로 형식을 제한합니다.
- 다만 모든 요청 DTO가 동일한 수준으로 체계화됐는지는 별도 전체 감사가 필요합니다. 현재는 핵심 공개 입력부 중심으로 보강된 상태입니다.

### A5. Rate limiting 없음

- **판정: 해결**
- `UserRateLimitFilter.java:22-27`, `UserRateLimitFilter.java:64-79`에서 `/api/analysis/upload`, `/api/analysis/{jobId}/run`, `/retry` 요청을 사용자별 버킷으로 분리합니다.
- `UserRateLimiter.java:32-49`는 Redis 기반 카운터/TTL로 제한을 적용하고, `application.yaml:74-80`에서 업로드/분석 실행 제한값을 환경변수로 조정할 수 있습니다.
- 범위는 비용이 큰 업로드/분석 실행 API 중심입니다. IP 기반 DDoS 방어, 전역 WAF 수준의 제한은 아직 별도 과제입니다.

### A6. CORS origin 하드코딩

- **판정: 해결**
- `application.yaml:70-72`에서 `CORS_ALLOWED_ORIGINS` 환경변수를 읽고, `CorsConfig.java:11-14`, `CorsConfig.java:30-40`에서 쉼표 구분 origin 목록을 적용합니다.
- 배포 도메인 변경 시 코드 수정 없이 환경변수로 대응할 수 있습니다.

### A7. 비밀값 커밋 위험

- **판정: 부분해결**
- 내부 엔진 키와 OpenAI 키는 `application.yaml:35-50`에서 `${INTERNAL_ENGINE_API_KEY:}`, `${OPENAI_API_KEY:}` 형태로 외부 주입합니다.
- dev/prod DB 설정은 `application-dev.yml:4-8`, `application-prod.yml:1-10`에서 환경변수 기반으로 분리됐고, prod는 DB 값에 기본값을 두지 않습니다.
- `.env.example`은 플레이스홀더/빈 값 중심으로 정리됐습니다.
- 다만 `SecurityConfig.java:80-85`에는 로컬 개발용 JWT secret 기본값이 남아 있습니다. 운영에서는 반드시 `SECURITY_JWT_SECRET` 계열 설정을 주입하도록 배포 체크리스트가 필요합니다.

### B1. 모델을 매 요청마다 다시 로딩

- **판정: 미해결**
- `basic_analysis.py:531-533`에서 `WhisperModel`이 함수 호출 중 생성됩니다.
- `basic_analysis.py:621-635`에서 `PoseLandmarkerOptions` 생성 후 `PoseLandmarker.create_from_options(...)`가 요청 처리 중 호출됩니다.
- `basic_analysis.py:965-981`에서도 `FaceLandmarker.create_from_options(...)`가 요청 처리 중 호출됩니다.
- 모델 싱글톤/프로세스 시작 시 프리로딩/워커 재사용 구조는 아직 도입되지 않았습니다. analysis-engine 성능에서 가장 큰 잔여 병목 중 하나입니다.

### B2. 동시 처리량 제한 없음

- **판정: 부분해결**
- `AsyncConfig.java:16-29`에서 `ThreadPoolTaskExecutor`를 core 2, max 4, queue 20으로 제한하고, `AnalysisCommandService.java:196-210`에서 트랜잭션 커밋 후 백그라운드 스레드풀에 분석 작업을 넘깁니다.
- 단, 이는 backend 단일 프로세스 내부 스레드풀입니다. Redis/RabbitMQ/SQS 같은 분산 작업 큐가 아니므로, 다중 인스턴스 환경에서는 job이 특정 인스턴스에 묶이고 장애 복구/재분배가 제한됩니다.

### B3. 백엔드↔분석엔진 호출 타임아웃 없음

- **판정: 해결**
- A3와 동일하게 `RestClientConfig.java:17-21`, `RestClientConfig.java:31-39`에서 공통 타임아웃이 적용됩니다.

### B4. 로컬 파일 저장 + 인메모리 DB로 수평 확장 불가

- **판정: 부분해결**
- DB는 dev/prod에서 MySQL + Flyway로 이동했습니다. `application-dev.yml:1-17`, `application-prod.yml:1-20`이 MySQL datasource와 Flyway를 설정하고, `backend/src/main/resources/db/migration/V1__init_schema.sql:5-29` 이후 V2~V5 마이그레이션이 존재합니다.
- 그러나 파일은 여전히 로컬 디스크입니다. `application.yaml:59-68`의 `storage.upload-path`, `storage.result-path`, `storage.temp-path`가 로컬 경로를 가리키고, `FilePathGenerator.java:17-40`, `VideoFileCommandService.java:40-45`가 `storage/uploads`, `storage/results`, `storage/temp` 기반으로 동작합니다.
- S3/MinIO/NFS 같은 공유 스토리지나 오브젝트 스토리지 추상화는 아직 없습니다.

### B5. 결과 목록 페이지네이션 없음 + UploadedVideo N+1

- **판정: 해결**
- `ResultController.java:44-58`, `ResultController.java:103-110`에서 `page`, `size`를 받고 size를 최대 100으로 제한합니다.
- `ResultQueryService.java:56-69`는 `Page<AnalysisJob>`를 사용하고, `ResultQueryService.java:72-85`는 현재 페이지의 jobId를 모아 `UploadedVideoRepository.findAllByJobIdIn(...)`로 한 번에 조회합니다.
- `readFinalResultSafely()`의 결과 JSON 파일 읽기는 남아 있지만, 지적된 DB N+1은 제거됐습니다.

### B6. 같은 jobId 동시 재실행 락 없음

- **판정: 해결**
- `AnalysisJob.java:16-20`에 `@Version` 낙관적 락이 추가됐습니다.
- `AnalysisCommandService.java:183-194`에서 실행 시작 상태 저장 시 `saveAndFlush()`를 사용하고, 동시 수정 실패를 `ANALYSIS_ALREADY_RUNNING`으로 변환합니다.

### B7. 프론트엔드 ErrorBoundary 없음

- **판정: 해결**
- `frontend/src/components/common/ErrorBoundary.jsx:8-45`에 렌더링 오류를 잡는 ErrorBoundary가 추가됐고, `frontend/src/App.jsx:5-10`에서 전체 라우트를 감쌉니다.
- API 실패는 기존 페이지별 처리와 별개로, 예상치 못한 렌더링 오류에 대한 마지막 방어선이 생겼습니다.

### 2026-07-03까지 추가 완료된 주요 항목

- **인증/소유권 검증**: `AuthController.java:38-83`에서 회원가입/로그인/JWT 발급을 처리하고, `SecurityConfig.java:62-71`에서 `/api/**` 인증을 요구합니다. `SecurityConfig.java:181-189`는 인증 객체 details에 userId를 심고, `ResultQueryService.java:120-126`은 결과 조회 소유권을 검증합니다.
- **분석 상태/진행률/실행/재시도 소유권 검증**: `AnalysisController.java:72-150`에서 인증 사용자 id를 서비스 호출에 전달하고, 진행률 조회도 Redis 캐시를 읽기 전에 DB 상태 조회로 소유권을 검증합니다.
- **분석 취소**: `AnalysisCommandService.java:155-174`에서 취소 요청을 받고, `AnalysisJob.java:143-164`, `AnalysisCommandService.java:387-400`에서 협조적 취소 상태 전환을 처리합니다.
- **멈춘 작업 워치도그**: `StuckAnalysisJobWatchdogService.java:24-30`, `StuckAnalysisJobWatchdogService.java:52-83`에서 오래 실행 중인 job을 주기적으로 FAILED 처리합니다.
- **OpenAI 비용 관리**: `OpenAiClient.java:107-130`에서 `OPENAI_USAGE` 토큰 사용량 로그를 남기고, `ResultCommandService.java:100-126`, `AnalysisCommandService.java:322-333`에서 기존 `REAL` OpenAI 응답을 재사용해 재시도 중복 호출을 줄입니다.
- **구조화 파일 로깅**: `logback-spring.xml:3-8`, `logback-spring.xml:17-28`, `logback-spring.xml:38-40`에서 콘솔 + rolling file 로그를 구성합니다.
- **Actuator 헬스체크**: `application.yaml:26-33`, `SecurityConfig.java:64-67`에서 `/actuator/health`만 공개하고 세부 노출은 제한합니다.
- **스토리지 정리 스케줄러**: `StorageCleanupService.java:41-67`, `StorageCleanupService.java:73-77`에서 오래된 temp와 DB에 없는 upload/result 고아 디렉토리를 정리합니다.
- **MySQL 백업 스크립트**: `scripts/backup-mysql.sh:30-35`, `scripts/backup-mysql.sh:60-86`에서 `mysqldump | gzip` 백업과 보존 기간 삭제를 수행합니다. 단, 현재까지 실제 MySQL 인스턴스 대상 검증은 완료되지 않았고, fake `mysqldump` 대체 검증만 수행된 한계가 있습니다.
- **pytest/CI 매트릭스**: `.github/workflows/verify.yml:11-67`에서 backend, frontend, analysis-engine, video-llm-engine 검증 job을 분리합니다. `analysis-engine/tests/test_basic_analysis_scoring.py`, `analysis-engine/tests/test_security.py`, `video-llm-engine/tests/test_security.py`, `video-llm-engine/tests/test_video_llm_analysis.py`가 추가됐습니다.

### 2026-07-03 기준 가장 큰 잔여 리스크

1. **Video LLM은 아직 mock입니다.** `video-llm-engine/app/api/video_llm_analysis.py:25-35`는 `mock-video-llm` 결과를 반환합니다. 서비스 품질 관점에서 가장 큰 미해결 과제입니다.
2. **분산 작업 큐가 아닙니다.** 현재 비동기 처리는 backend 내부 `ThreadPoolTaskExecutor` 기반입니다. 다중 인스턴스에서 작업 재분배, 중복 실행 방지, 작업 소유권 이전을 보장하지 않습니다.
3. **스케줄러에 분산 락이 없습니다.** `StorageCleanupService`, `StuckAnalysisJobWatchdogService`는 `@Scheduled`로 동작하므로 backend 인스턴스를 여러 대 띄우면 같은 정리/워치도그 작업이 중복 실행될 수 있습니다.
4. **파일 저장소가 로컬입니다.** MySQL 전환은 됐지만 업로드/결과 파일은 여전히 로컬 디스크에 묶여 있습니다.
5. **analysis-engine 모델 프리로딩이 없습니다.** MediaPipe/Whisper 모델 생성이 요청 경로에 남아 있어 성능/지연시간 리스크가 큽니다.
6. **백업은 스크립트만 있고 운영 검증이 부족합니다.** 실제 MySQL 대상 백업/복구 리허설, 원격 보관, 암호화, 알림은 아직 없습니다.

---

## 업데이트: 2026-07-04 현재 상태

2026-07-03 업데이트 이후 완료된 S/O/Q 계열 작업과 analysis-engine 프리로딩 작업까지 반영해 A/B 항목을 다시 확인했습니다. 기존 2026-07-02/07-03 본문은 당시 스냅샷으로 남깁니다.

### A1-A7 최신 판정

| 항목 | 2026-07-04 판정 | 근거 |
| --- | --- | --- |
| A1. analysis-engine/video-llm-engine 인증 없는 파일 접근 | 해결 | 두 엔진 라우터는 내부 키 의존성을 사용합니다(`analysis-engine/app/api/basic_analysis.py:18-22`, `video-llm-engine/app/api/video_llm_analysis.py:11-15`). backend는 엔진 키를 환경변수로 읽습니다(`application.yaml:40-47`). |
| A2. 업로드 파일 매직바이트 검증 없음 | 부분해결 | 확장자/시그니처 검증은 `VideoFileCommandService.java:77-88`, `VideoFileCommandService.java:108-117`에 있고, 저장 전 파일 내용 불일치를 거부합니다. 영상 재생 시간 제한은 아직 없습니다. |
| A3. inter-service timeout 없음 | 해결 | `RestClientConfig.java:17-21`에 connect/read timeout이 있고, `RestClientConfig.java:31-39`에서 공통 `RestClient.Builder`에 적용됩니다. OpenAI timeout 설정은 `application.yaml:51-55`에 있습니다. |
| A4. 입력 검증 미흡 | 부분해결 | auth 요청은 `AuthController.java:141-149`, 회원탈퇴 요청은 `UserController.java:49-52`에서 Bean Validation을 사용합니다. 다만 영상 길이/사용자별 용량/개수 정책은 아직 부분적입니다. |
| A5. rate limiting 없음 | 해결 | 업로드/분석 실행 제한에 더해 로그인도 `AuthController.java:89-95`, `UserRateLimiter.java:36-72`, `application.yaml:85-94`에서 제한합니다. |
| A6. CORS origin 하드코딩 | 해결 | `application.yaml:81-83`에서 `CORS_ALLOWED_ORIGINS` 환경변수를 사용합니다. |
| A7. 비밀값 커밋 위험 | 부분해결 | 내부 엔진 키/OpenAI/Redis는 환경변수 기반입니다(`application.yaml:20-24`, `application.yaml:40-55`). 단 `SecurityConfig.java:81-86`에는 로컬 개발용 JWT secret 기본값이 남아 운영 배포 체크가 필요합니다. |

### B1-B7 최신 판정

| 항목 | 2026-07-04 판정 | 근거 |
| --- | --- | --- |
| B1. 모델을 매 요청마다 다시 로딩 | 해결 | `model_registry.py:43-47`에서 프리로딩을 제공하고, `model_registry.py:50-143`에서 Whisper/Pose/Face singleton과 inference lock을 제공합니다. FastAPI lifespan은 `analysis-engine/app/main.py:16-27`에서 `preload_all()`을 호출합니다. 분석 함수는 `basic_analysis.py:487`, `basic_analysis.py:569`, `basic_analysis.py:898`에서 registry context를 사용합니다. |
| B2. 동시 처리량 제한 없음 | 부분해결 | backend 내부 executor는 `AsyncConfig.java:21-35`에서 core/max/queue와 shutdown wait를 설정합니다. 단 `AnalysisCommandService.java:196-210`처럼 여전히 프로세스 내부 스레드풀 실행이며 분산 큐가 아닙니다. |
| B3. 백엔드↔분석엔진 호출 타임아웃 없음 | 해결 | 외부 호출 timeout 설정이 유지되며 OpenAI timeout은 `application.yaml:51-55`에서 환경변수화됐습니다. |
| B4. 로컬 파일 저장 + 인메모리 DB로 수평 확장 불가 | 부분해결 | DB/Flyway는 운영형으로 이동했지만 파일 경로는 `FilePathGenerator.java:17-39`처럼 로컬 `storage` 기반입니다. 공유 스토리지/S3/MinIO는 없습니다. |
| B5. 결과 목록 페이지네이션 없음 + UploadedVideo N+1 | 해결 | 이전 07-03 판정과 동일하게 페이지네이션/배치 조회가 적용된 상태입니다. |
| B6. 같은 jobId 동시 재실행 락 없음 | 해결 | 낙관적 락과 실행 상태 전환 보호가 유지됩니다. |
| B7. 프론트엔드 ErrorBoundary 없음 | 해결 | 이전 07-03 판정과 동일하게 ErrorBoundary가 존재하고 App에 적용된 상태입니다. |

### 2026-07-04까지 추가 완료된 주요 항목

- **로그아웃/토큰 무효화(S2)**: `AuthController.java:110-120`, `JwtBlacklist.java:27-52`, `SecurityConfig.java:183-187`에서 Redis 기반 JWT 블랙리스트와 로그아웃 API를 제공합니다.
- **회원탈퇴/데이터 삭제(S3)**: `UserController.java:23-33`, `UserWithdrawalService.java:40-57`, `UserWithdrawalService.java:60-80`에서 비밀번호 재확인 후 소유 job/파일/계정을 삭제합니다.
- **로그인 rate limit(S4)**: `AuthController.java:89-95`, `UserRateLimiter.java:36-72`, `application.yaml:92-94`에서 이메일 기준 로그인 시도를 제한합니다.
- **docker-compose 포트/Redis 보안(S5)**: `docker-compose.yml:30-50`, `docker-compose.yml:64-83`, `docker-compose.yml:114-116`에서 내부 서비스 포트를 loopback으로 제한하고 Redis 비밀번호를 적용합니다.
- **nginx/TLS 스캐폴딩(S6)**: `infra/nginx/nginx.conf:1-31`, `docker-compose.prod.yml:5-34`에서 nginx TLS 종단, certbot renew, backend/frontend 직접 포트 제거 오버레이를 제공합니다. 실제 인증서 발급은 별도 서버 검증 필요.
- **graceful shutdown(O1)**: `application.yaml:1-13`, `AsyncConfig.java:21-30`, `docker-compose.yml:87-92`에서 Spring graceful shutdown, executor 종료 대기, Compose stop grace period를 설정합니다.
- **컨테이너 리소스 제한(O2)**: `docker-compose.yml:20-24`, `docker-compose.yml:43-47`, `docker-compose.yml:56-60`, `docker-compose.yml:73-78`, `docker-compose.yml:93-97`, `docker-compose.yml:147-151`에서 6개 서비스의 CPU/메모리 상한을 설정합니다. 실제 컨테이너 inspect 검증은 아직 없습니다.
- **원본 영상 보존 기간(O4)**: `OriginalVideoRetentionService.java:52-63`, `OriginalVideoRetentionService.java:85-99`, `OriginalVideoRetentionServiceTest.java:69-99`에서 오래된 COMPLETED job의 원본 업로드만 정리합니다.
- **CI 보안감사(Q1)**: `.github/workflows/verify.yml:38-89`, `.github/dependabot.yml:1-26`에서 npm audit, pip-audit, Docker build matrix, dependabot을 추가했습니다. Python audit는 현재 취약점 때문에 실패 허용입니다.
- **API 계약 자동검증(Q2)**: `build.gradle:21-31`, `SecurityConfig.java:62-68`, `ApiContractTest.java:47-73`에서 springdoc/OpenAPI와 프론트 API 호출-백엔드 라우트 계약 검증을 추가했습니다.
- **Python 3.13 Dockerfile 정렬**: `analysis-engine/Dockerfile:1`, `video-llm-engine/Dockerfile:1`, `.github/workflows/verify.yml:76-89`에서 Python 엔진 Docker 이미지와 CI Python 버전을 맞추고 docker-build fail-fast를 껐습니다.
- **O3 업로드 용량/저장 공간 대응**: `ErrorCode.java:13-14`, `GlobalExceptionHandler.java:63-70`, `StorageProperties.java:5-13`, `VideoFileCommandService.java:91-106`, `VideoFileCommandServiceTest.java:70-92`에 구현됐습니다. 단 전체 Gradle 테스트 통과는 아직 환경 제약으로 확인되지 않았고, 영상 길이 제한은 미구현입니다.

### 2026-07-04 기준 가장 큰 잔여 리스크

1. **Video LLM은 여전히 mock입니다.** `video-llm-engine/app/api/video_llm_analysis.py:25-79`는 입력 영상과 무관한 `mock-video-llm` 응답을 반환합니다.
2. **JWT가 localStorage에 저장됩니다.** `AuthContext.jsx:8-39`, `apiClient.js:12-36`은 access token을 localStorage에서 읽고 씁니다. XSS 방어와 저장 전략 재검토가 필요합니다.
3. **분산 작업 큐와 공유 파일 스토리지가 없습니다.** 분석은 `AnalysisCommandService.java:196-210`의 내부 executor 기반이고, 파일은 `FilePathGenerator.java:17-39`의 로컬 경로 기반입니다.
4. **O3는 구현됐지만 테스트 통과 확인과 영상 길이 제한이 남았습니다.** 저장 공간/413 응답 코드는 있으나 전체 테스트 실행 확인이 아직 없고, `basic_analysis.py:272-308`은 duration을 계산만 합니다.
5. **백업은 실제 복구 리허설이 부족합니다.** `scripts/backup-mysql.sh:60-86`은 백업/보존을 수행하지만 실제 MySQL 복구, 원격 보관, 암호화, 알림은 남아 있습니다.
6. **운영 관측성은 health 중심입니다.** Actuator health, 파일 로그는 있으나 metrics/alerting/Prometheus/Grafana 연동은 아직 없습니다.
