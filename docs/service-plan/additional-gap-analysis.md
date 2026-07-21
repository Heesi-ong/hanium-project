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

---

## 업데이트: 2026-07-15 현재 상태

2026-07-04 업데이트 이후 완료된 작업(NVIDIA Video LLM 실연동, JWT 쿠키 전환, 분산 워커 폴러, 모니터링 스택, 백업 자동화, 관리자 대시보드 등)을 반영해 A/B 항목과 잔여 리스크를 다시 확인했다. 기존 업데이트 본문은 스냅샷으로 남겨둔다.

### 주요 변화 요약

| 항목 | 07-04 판정 | 07-15 판정 | 근거 |
| --- | --- | --- | --- |
| Video LLM 실제 모델 연동 | 미해결(mock) | 부분해결 | `video-llm-engine/app/api/video_llm_analysis.py:64-146`에 NVIDIA API Catalog(`nvidia/nemotron-3-nano-omni-30b-a3b-reasoning`) 실호출 코드가 완성돼 있고 예외 시 mock 폴백. 다만 기본값은 여전히 비활성(`video-llm-engine/.env:14` `VIDEO_LLM_ENABLED=false`, `:38` `VIDEO_LLM_BACKEND=mock`). |
| JWT 저장 위치 | localStorage(최대 잔여 리스크) | 해결 | `frontend/src/api/apiClient.js`가 `withCredentials: true`만 쓰고 토큰을 localStorage에 저장하지 않음. `AuthController.java:166-169`, `JwtCookieSupport.java:32-38`에서 httpOnly+secure+SameSite=Lax 쿠키로 발급. 단 `AuthContext.test.jsx`/`apiClient.test.js` 등 구 테스트가 localStorage 가정을 그대로 갖고 있어 정리가 필요할 수 있음. |
| 비동기 작업 큐 | 내부 스레드풀만 | 부분해결 | `AsyncConfig.java`는 여전히 `ThreadPoolTaskExecutor` 기반이지만, `QueuedAnalysisJobPoller`가 DB에서 QUEUED 작업을 원자적 claim해 `analysis-worker` 컨테이너로 분리 실행 가능(`docker-compose.yml:224-297`, `--scale analysis-worker=N`). 진짜 메시지 브로커(재시도/데드레터/우선순위)는 아님. |
| 파일 저장소 | 로컬 디스크 | 미해결 | `FilePathGenerator.java:17-75`, `application.yaml:161-166` 전부 로컬 경로 기반. S3/MinIO 등 공유 스토리지 없음. **현재 가장 크게 남은 구조적 갭.** |
| 스케줄러 분산 락 | 없음 | 해결 | `StorageCleanupService.java`, `StuckAnalysisJobWatchdogService.java`가 `SchedulerDistributedLock`(Redis SETNX 기반) 사용. 단 Redis 장애 시 fail-open(락 없이 실행)이라는 트레이드오프가 있음(`SchedulerDistributedLock.java:33-36`). |
| 모니터링/관측성 | health 중심 | 해결 | `docker-compose.monitoring.yml`에 prometheus/node-exporter/cadvisor/grafana/alertmanager 5종, `infra/prometheus/alerts.yml`, `infra/grafana/provisioning/dashboards/json/*.json` 실존. backend `/actuator/prometheus` 노출. |
| 백업 실제 검증 | fake mysqldump 수준 | 부분해결 | `storage/backups/`에 실제 MySQL 대상 반복 백업 파일과 `storage/logs/backup.log` 존재(fake 아님). 단 `scripts/restore-mysql.sh`는 있으나 실제 복구 리허설 로그는 확인 안 됨, CI에 백업/복구 job도 없음. |
| 영상 길이 제한(O3) | 계산만, 제한 미적용 | 해결 | `VideoFileCommandService.java:102-108`에서 `VIDEO_MAX_DURATION_MINUTES`(기본 30분) 초과 시 거부. `FfprobeVideoDurationProbe.java`는 ffprobe 실행 실패 시 fail-open으로 통과시키는 정책이 남아 있음. |
| 관리자 대시보드/권한 | 미착수 | 해결 | `SecurityConfig.java:78`에서 `/api/admin/**`에 `hasRole("ADMIN")` 적용. `AdminController.java`에 사용자 목록/통계/상세조회/감사로그/정지·활성화/강제탈퇴/결과삭제 10개 엔드포인트 완비. |
| CI 구조 | backend/frontend/python 기본 | 해결 | `.github/workflows/verify.yml`에 backend, frontend(lint+test+build+audit), python-engines matrix(analysis-engine+video-llm-engine, pip-audit+pytest), docker-build matrix(4개 서비스+Trivy), compose-validate까지 포함. |
| DB 마이그레이션 | V5까지 | 해결 | `backend/src/main/resources/db/migration/`이 V1~V14까지 진행. dev/prod 모두 MySQL+Flyway 유지. |

### 2026-07-15 기준 가장 큰 잔여 리스크 (우선순위 순)

1. **파일 저장소가 여전히 로컬 디스크입니다.** `analysis-worker`를 여러 인스턴스로 수평 확장할 수 있는 구조(claim 기반 폴러)는 갖췄지만, 업로드/결과 파일이 로컬 디스크에 있는 한 다중 인스턴스 배포 시 파일 접근 문제가 그대로 남습니다. 이제 다른 A/B 항목이 대부분 해소된 만큼, 우선순위가 가장 높은 잔여 갭입니다.
2. **Video LLM 실연동이 기본 비활성 상태입니다.** 코드는 완성됐지만 `VIDEO_LLM_ENABLED=false`가 기본값이라 실제 운영에서 켜려면 별도 결정과 비용/실패 정책 점검이 필요합니다.
3. **백업 복구 리허설이 없습니다.** 백업 자체는 실제 MySQL 대상으로 검증됐지만, 복구가 실제로 되는지 확인된 적이 없습니다.
4. **스케줄러 분산 락이 Redis 장애 시 fail-open입니다.** 의도된 트레이드오프이지만, Redis 다운 상황에서 스케줄러 중복 실행 가능성이 문서화되어 있어야 합니다.
5. **구 프론트 테스트 파일이 localStorage 가정을 갖고 있습니다.** 실제 구현(쿠키 기반)과 테스트 가정이 어긋나 있어 테스트가 실제 동작을 검증하지 못하고 있을 가능성이 있습니다(`AuthContext.test.jsx`, `apiClient.test.js` 확인 필요).

---

## 업데이트: 2026-07-16 현재 상태

2026-07-15 업데이트 이후 완료된 파일 저장소 MinIO 마이그레이션(Phase A-F)과, 이미 완료됐으나 이 문서에 반영되지 않았던 백업 복구 리허설/Redis fail-open 문서화 작업을 반영해 잔여 리스크를 다시 확인했다. 기존 07-15 업데이트 본문은 스냅샷으로 남겨둔다.

### 주요 변화 요약

| 항목 | 07-15 판정 | 07-16 판정 | 근거 |
| --- | --- | --- | --- |
| 파일 저장소 | 미해결(로컬 디스크) | 부분해결 | Phase A-F로 MinIO 이중 쓰기(`VideoFileCommandService.java:85-99`, `JsonFileStorage.java:57-75`), 엔진용 presigned 다운로드 URL(`VideoFileCommandService.java:106-118`, `analysis-engine/app/api/basic_analysis.py`, `video-llm-engine/app/api/video_llm_analysis.py`), 브라우저 스트리밍 presigned 리다이렉트(`VideoFileCommandService.java:127-144`, `ResultController.java`, internal/public endpoint 분리), 정리 스케줄러의 MinIO prefix 삭제(`StorageCleanupService.java`, `OriginalVideoRetentionService.java`), 기존 로컬 파일 백필 러너(`ObjectStorageBackfillRunner.java`)까지 완료됐다. 다만 로컬 디스크 fallback 코드는 안전망으로 의도적으로 유지 중이며, 실제 운영 MinIO 환경에서 백필 스크립트를 돌려본 리허설은 아직 없다. |
| 백업 복구 리허설 | 없음 | 해결 | `docs/ops/backup-restore-runbook.md`와 `storage/logs/restore-rehearsal-20260715_184840.log`에 2026-07-15 실제 리허설 기록이 있다. 일회용 `mysql:8.4` 컨테이너에 실제 백업 파일(`hanium_dev_20260714_065215.sql.gz`)을 복구해 테이블 5개/약 13행이 정상 복구됐음을 확인했다. |
| 스케줄러 분산 락 fail-open 문서화 | 미문서화 | 해결 | `docs/ops/scheduler-distributed-lock.md`에 fail-open 정책과 트레이드오프, 향후 개선 방향(Prometheus 메트릭화)이 문서화됐다. 정책 자체(Redis 장애 시 락 없이 실행)는 의도된 설계로 유지된다. |
| 프론트 localStorage 테스트 가정 | 확인 필요 | 해결(문제 아니었음) | `apiClient.test.js:10-14`는 localStorage에 스토어 토큰을 일부러 심어두고 Authorization 헤더로 전송되지 않는지 검증하는 테스트이고, `AuthContext.test.jsx`도 로그인/로그아웃 후 localStorage가 비어 있는지(즉 쿠키 전용 인증에 토큰이 새지 않는지) 확인하는 테스트다. 실제로는 쿠키 기반 인증과 일치하는 안전장치 테스트였다. |

### 2026-07-16 기준 가장 큰 잔여 리스크 (우선순위 순)

1. **Video LLM 실연동이 기본 비활성 상태입니다.** 코드는 완성됐지만 `VIDEO_LLM_ENABLED=false`가 기본값이라 실제 운영에서 켜려면 비용/실패 정책 재점검이 필요합니다. 다른 A/B 항목이 대부분 해소된 지금 가장 우선순위가 높은 잔여 갭입니다.
2. **MinIO 백필 스크립트가 실제 운영 환경에서 리허설되지 않았습니다.** `ObjectStorageBackfillRunner`는 코드/단위 테스트로만 검증됐고, 실제 MinIO가 떠 있는 환경에서 `STORAGE_BACKFILL_ENABLED=true`로 1회 실행해 본 적은 없습니다.
3. **로컬 디스크 fallback 제거 여부가 결정되지 않았습니다.** 현재는 MinIO 실패 시 로컬 디스크로 계속 동작하는 이중 구조입니다. 다중 인스턴스 수평 확장을 완전히 전제하려면 로컬 fallback을 언제 걷어낼지 별도 결정이 필요합니다.
4. **원격 백업 보관과 암호화가 없습니다.** 복구 리허설은 완료됐지만, 백업 파일은 여전히 로컬 `storage/backups/`에만 있고 원격 저장소 반출이나 암호화는 없습니다.

---

## 업데이트: 2026-07-17 현재 상태

2026-07-16 이후 실제 MinIO·MySQL·멀티워커 검증과 운영 보강 결과를 반영한다. 이전 절은 당시 스냅샷으로 유지한다.

### 완료된 잔여 리스크

- **MinIO 백필과 다중 워커**: 일회용 실제 MinIO에서 백필 최초 실행과 재실행 idempotency를 검증했고, 서로 다른 로컬 저장 경로를 쓰는 API 1개+worker 2개가 영상 4건을 각각 2건씩 처리했다. API 로컬 결과가 0개인 상태에서도 MinIO 결과 4건을 조회했다. 상세 기록은 `docs/ops/minio-backfill-and-fallback-plan.md`에 있다.
- **prod 저장 정책**: 신규 업로드·결과의 MinIO 쓰기를 필수로 만들고 결과 JSON은 MinIO 우선 읽기로 전환했다. 기존 파일 보호를 위한 로컬 읽기 fallback만 유지한다.
- **백업 원격 반출·암호화**: MySQL 덤프의 MinIO 반출과 AES-256-CBC/PBKDF2 암호화, 잘못된 키 실패, 실제 MySQL 복구와 데이터 대조를 완료했다.
- **P4 관측성·부하**: ffprobe fail-open 및 Video LLM 폴백 카운터와 알림을 추가했고, k6 시나리오에서 의도한 429를 실패율로 오인하지 않도록 검증했다.

### analysis-engine 모델 풀 운영 보강

- 모델 재사용 자체는 `model_registry.py` 인스턴스 풀과 FastAPI lifespan으로 이미 해결된 상태였다.
- 풀 크기 환경변수가 0·음수·비정수이면 요청이 무기한 대기할 수 있어, 프로세스 시작 시 1 이상의 정수만 허용하도록 fail-fast 검증을 추가했다.
- 루트 `.env.example`, `analysis-engine/.env.example`, `docker-compose.yml`에 Whisper/Pose/Face 풀 크기를 연결했다.
- FastAPI lifespan 종료 시 모델 인스턴스를 닫고 readiness 카운터를 초기화한다. 레지스트리와 lifecycle을 포함한 analysis-engine 전체 테스트 63개가 통과했다.
- Compose는 두 엔진에 HTTP healthcheck를 두고 backend와 analysis-worker가 `service_healthy`까지 기다리도록 변경했다. 최초 모델 프리로드 중 backend가 먼저 올라와 초기 분석 요청이 실패하는 기동 순서 위험을 제거했으며, CI가 렌더링된 의존 조건을 검사한다.
- Redis도 인증 healthcheck와 `service_healthy` 의존 조건을 적용했다. 기존 command가 공백 포함 비밀번호를 여러 인자로 분리하던 문제를 컨테이너 환경변수 기반 실행으로 수정했으며, 실제 `redis:7-alpine`에서 공백 포함 키의 `healthy`/`PONG`과 잘못된 키 거부를 검증했다.
- backend·analysis-worker·backup은 MinIO 서버 준비뿐 아니라 `minio-init`의 버킷 생성 성공까지 기다린다. 격리 Compose에서 `MinIO Healthy → minio-init exit 0 → 두 버킷 확인 소비자 실행` 순서와 초기화 재실행 종료 코드 0을 검증했다.
- MySQL healthcheck를 인증 없는 `mysqladmin ping`에서 root 인증 TCP `SELECT 1`로 강화했다. 실제 `mysql:8.4`에서 공백 포함 root 키의 `healthy`/쿼리 성공과 잘못된 키의 종료 코드 1·`Access denied`를 검증했으며, CI가 MySQL을 포함한 핵심 의존성 전체의 health 계약을 검사한다.
- backup은 더 이상 컨테이너 시작 시 AMD64 전용 `mc`를 다운로드하지 않는다. 공식 멀티 아키텍처 `minio/mc`에서 바이너리를 포함한 전용 이미지를 빌드하며, ARM64에서 실제 MySQL 덤프→AES-256 암호화→MinIO 업로드→다운로드 해시 일치→복호화 데이터 대조까지 검증했다. CI도 백업 이미지를 빌드하고 필수 도구 존재를 확인한다.
- 로컬 덤프 성공만 기록하고 MinIO 반출 실패는 경고 로그로만 남던 관측 공백을 해소했다. 로컬·원격 상태와 마지막 성공 시각을 독립 메트릭으로 기록하고, 운영에서는 원격 실패를 종료 코드 1로 전파하며, 원격 실패·26시간 정체 알림과 셸 회귀 테스트를 추가했다.
- 엔진용 presigned URL이 객체 존재 여부 확인 없이 생성돼 백필 누락 파일마다 엔진 다운로드 timeout 후 로컬 fallback으로 늦게 전환되던 문제를 수정했다. 실제 MinIO 통합 테스트에서 누락 객체는 URL을 생성하지 않고 즉시 로컬 fallback 신호(`null`)를 반환함을 확인했다.
- Video LLM 엔진이 최대 500MB 영상을 MinIO 응답과 NVIDIA Asset PUT에서 통째로 `bytes`에 적재하던 메모리 위험을 제거했다. 다운로드는 크기 제한 임시 파일, Asset PUT은 `Content-Length`가 있는 1MB 청크 스트림으로 처리하고 임시 파일은 성공·실패 모두 정리한다.
- analysis-engine의 MinIO 다운로드에도 500MB 상한을 헤더·누적 스트림 양쪽에 적용했다. 기존에는 연결 중단·HTTP 오류·빈 응답·과대 객체에서 부분 파일이 남을 수 있었으나, 이제 모든 실패 경로에서 부분 파일과 응답 연결을 정리한다.
- 두 Python 엔진의 영상 크기 상한은 FastAPI lifespan에서 1 이상의 정수인지 검증한다. 잘못된 운영값은 첫 분석 요청까지 숨지 않고 프로세스 기동을 fail-fast로 중단한다.
- Starlette 1.3.1 TestClient가 기존 `httpx` fallback을 폐기할 예정인데도 HTTPX2가 requirements에 없고 로컬 venv 잔존 패키지에 의존하던 CI 재현성 문제를 해소했다. HTTPX2 2.7.0과 전이 의존성을 고정했으며 깨끗한 Python 3.13 venv 설치·전체 테스트에서 경고 없이 통과했다.
- analysis-engine 배포 이미지는 Python 3.12 전용 MediaPipe/OpenCV 분기를 사용하지만 기존 CI pytest는 Python 3.13만 실행하던 런타임 불일치를 해소했다. docker-build job이 실제 Python 3.12 이미지에 테스트만 읽기 전용 마운트해 전체 pytest와 `pip check`를 수행하고, Video LLM runtime 이미지도 앱 import·설정·의존성 smoke를 통과해야 한다.

### 남은 외부 환경 항목

1. staging 기존 `storage/uploads`, `storage/results` 전체 백필과 객체 수 대조
2. staging MinIO 강제 중단 시 신규 쓰기 실패 동작 확인
3. 실제 NVIDIA Video LLM 활성화와 비용·국외 이전 정책 확정
4. 실제 운영 CPU/GPU에서 장시간 부하 측정 후 backend executor와 analysis-engine 모델 풀 크기 조정

---

## 업데이트: 2026-07-18 현재 상태

2026-07-17 업데이트 이후 두 차례 커밋(`a03ddd9` MinIO/OpenAI 자격증명 fail-fast 검증기 + ffmpeg timeout + Python 엔진 job_id 로그 상관관계, `7276cc6` backend↔엔진 requestId 상관관계 연동)이 반영됐다. 이번 회차는 코드 수정 없이 **새 문제점 발굴**만을 목적으로, 지금까지 다루지 않은 네 영역을 병렬로 조사했다: (1) 직전 두 커밋 자체에 대한 회귀 리뷰, (2) 인프라/배포 보안(Docker 이미지, nginx, 시크릿, 실제 의존성 취약점), (3) 로그인 세션이 필요해 이전 QA(`docs/qa-ui-report-2026-07-16.md`)가 점검하지 못했던 보호된 페이지, (4) `PrivacyPage.jsx`가 약속한 데이터 보존/삭제 정책과 실제 백엔드 코드의 일치 여부. 모든 발견은 코드를 직접 읽거나 실제 명령을 실행해 확인했다.

### 신규 발견 — 우선순위 순

#### N1. [Critical·컴플라이언스] 결과 삭제/회원탈퇴 시 MinIO 오브젝트가 지워지지 않음

- **문제**: `ResultCommandService.deleteResult()`(`backend/src/main/java/.../result/ResultCommandService.java:176-205`)는 로컬 디스크의 업로드/결과 디렉터리만 삭제하고, `objectStorage.deleteObjectsWithPrefix(...)`를 전혀 호출하지 않는다. 반면 `OriginalVideoRetentionService`(114-126행)와 `StorageCleanupService`(137-143행)는 같은 정리 작업에서 이미 이 메서드를 호출해 MinIO 프리픽스를 함께 지운다. `UserWithdrawalService.deleteOwnedResults()`도 결국 `deleteResult()`를 호출하므로 같은 결함을 그대로 물려받는다.
- **왜 중요한가**: MinIO 쓰기가 prod에서 필수로 강제된 지금(07-17 반영), 사용자가 "결과 삭제" 또는 "회원탈퇴"를 눌러도 업로드 영상·결과 파일의 MinIO 사본은 영구히 남는다. `PrivacyPage.jsx:106-110`은 "회원탈퇴가 처리되면... 업로드 영상, 결과 파일 삭제를 시도"한다고 명시하는데 실제로는 시도조차 하지 않는 경로가 존재한다 — 개인정보처리방침과 실제 동작이 어긋난다.
- **근거**: `backend/src/main/java/com/hanium/presentation/application/result/ResultCommandService.java:176-205`, 대조군 `OriginalVideoRetentionService.java:114-126`, `StorageCleanupService.java:137-143`.

#### N2. [Critical·CI] analysis-engine의 pillow가 실제 미패치 취약점 8건을 갖고 있어 CI가 막혀 있을 가능성

- **문제**: `analysis-engine/requirements.txt:41`이 고정한 `pillow==12.2.0`에 `pip-audit`로 실제 재현 확인한 취약점 8건(PYSEC-2026-2253/2254/2255/2256/2257/3451/3452/3453)이 있다. 전부 `12.3.0`에서 수정됐다.
- **왜 중요한가**: `.github/workflows/verify.yml:113-115`의 `pip-audit -r requirements.txt` 스텝은 (과거 "취약점 때문에 실패 허용"이었던 것과 달리) 현재 실패를 허용하지 않도록 강제돼 있다(`ca9504a`/`8d93fd6` 계열 커밋에서 강제화). 즉 지금 이 파일 상태로는 `python-engines / analysis-engine` CI job이 실패하고 있을 가능성이 높다.
- **근거**: `analysis-engine/requirements.txt:41`, 직접 실행한 `pip-audit -r requirements.txt` 결과(8건, 모두 pillow), `.github/workflows/verify.yml:113-115`.
- **부가 발견**: `video-llm-engine/requirements-real-model.txt`(로컬 모델 빌드 전용, torch/setuptools/pillow)는 CI가 아예 감사하지 않는다(`verify.yml`의 `python-engines` matrix는 `requirements-test.txt`만 사용). 직접 실행한 결과 `pillow==12.2.0`(동일 8건) + `setuptools==81.0.0`(PYSEC-2026-3447) + `torch==2.12.1`(PYSEC-2025-194) 등 3개 패키지에 10건이 잡힌다 — `VIDEO_LLM_BACKEND=local-model`로 빌드하는 경로는 CI에서 완전히 사각지대다.

#### N3. [High·보안] 실제 NVIDIA API 키가 담긴 `.env`가 Docker 빌드 컨텍스트에서 이미지에 그대로 복사될 수 있음

- **문제**: `video-llm-engine/Dockerfile`(과 `analysis-engine/Dockerfile`)이 `COPY . .`로 빌드 컨텍스트 전체를 복사하는데, 각 서비스의 `.dockerignore`는 `.venv/`, `__pycache__/`, `tests/`, `logs/`만 제외하고 **`.env`를 제외하지 않는다**. 루트 `.gitignore`는 `video-llm-engine/.env`를 이미 제외하고 있어 git에는 안전하지만, `.gitignore`는 `docker build`를 막지 못한다.
- **왜 중요한가**: 실제로 지금 디스크에 `video-llm-engine/.env`가 존재하고 `NVIDIA_API_KEY=nvapi-...`(실제 키로 보이는 값)를 담고 있다. 이 상태에서 누군가 `docker compose build` 또는 `docker build ./video-llm-engine`을 실행하면 이 키가 이미지 레이어에 그대로 박혀, 이후 `.env`를 지워도 `docker history`/이미지 push를 통해 복구 가능한 상태로 남는다.
- **근거**: `video-llm-engine/Dockerfile:25`(`COPY . .`), `video-llm-engine/.dockerignore`(`.env` 미포함), 디스크상 `video-llm-engine/.env` 실존 확인. `analysis-engine/Dockerfile:15`도 동일 패턴.

#### N4. [High·보안] analysis-engine/video-llm-engine/frontend/backup 컨테이너가 root로 실행됨

- **문제**: 5개 Dockerfile 중 `backend`(및 동일 이미지인 `analysis-worker`)만 `USER appuser`로 권한을 낮춘다. `frontend`(nginx), `analysis-engine`, `video-llm-engine`, `infra/backup`은 `USER` 지시자가 없어 root로 실행된다. `docker-compose.yml`에도 `cap_drop`/`read_only`/`no-new-privileges` 등 컨테이너 권한 하드닝이 어느 서비스에도 없다(의도적으로 `privileged: true`를 쓰는 모니터링용 `cadvisor`는 별개).
- **왜 중요한가**: analysis-engine과 video-llm-engine은 사용자가 업로드한 신뢰할 수 없는 영상/오디오를 ffmpeg·OpenCV·MediaPipe 같은 네이티브 코덱 라이브러리로 직접 처리한다. 이런 라이브러리는 파서 취약점(RCE) 이력이 꾸준히 있는 영역이라, 컨테이너 브레이크아웃급 취약점이 나오면 root 권한 그대로 노출된다.
- **근거**: `frontend/Dockerfile`, `analysis-engine/Dockerfile`, `video-llm-engine/Dockerfile`, `infra/backup/Dockerfile` — 4개 파일 모두 `USER` 지시자 없음. `docker-compose.yml` 전체에서 `cap_drop`/`read_only`/`no-new-privileges` grep 결과 0건(모니터링 오버레이의 `cadvisor: privileged: true` 제외).

#### N5. [Medium·UX 회귀] 로그인 상태 모바일(390px) 상단 네비게이션이 실제로 가로 오버플로우를 일으킴

- **문제**: 로그인한 사용자에게 보이는 헤더(홈/요금제/영상업로드/분석결과/시스템상태/계정설정/이메일뱃지/로그아웃)는 햄버거 메뉴로 접히지 않는다. 실제 브라우저에서 390px로 측정한 결과 `document.documentElement.scrollWidth=687` vs `clientWidth=390`으로 **297px 가로 오버플로우**가 발생한다.
- **왜 중요한가**: 이전 QA(07-16)는 로그인 없이 접근 가능한 공개 페이지만 점검했고 그때 헤더는 문제 없었다("홈/요금제/로그인/회원가입" 4개뿐이라 문제가 안 보였음). 로그인 후 헤더 항목이 늘어나며 새로 생긴, 지금까지 아무 QA에도 잡히지 않았던 회귀성 문제다. 768px 태블릿에서도 오버플로우는 없지만 각 메뉴 라벨이 "영/상/업/로/드"처럼 한 글자씩 세로로 쌓여 헤더 높이가 약 100px까지 부풀어 보인다.
- **추가 확인(관리자 계정)**: 관리자로 로그인하면 헤더에 "관리자" 링크가 하나 더 붙어 390px에서 `scrollWidth=718` vs `clientWidth=375`로 **343px 오버플로우**로 더 악화된다. 즉 이 문제는 로그인 사용자 전반의 문제이며, 권한이 높을수록(관리자) 더 심해진다.
- **근거**: 실제 로그인 세션으로 `/upload`, `/results` 등 인증 페이지를 390px/768px에서 렌더링해 `scrollWidth`/`clientWidth`를 JS로 직접 측정. 관리자 계정으로 `/admin`도 동일하게 측정.

#### N6. [Medium] 새로 추가한 requestId/job_id 상관관계 기능에 자동화 테스트가 전혀 없음

- **문제**: 직전 두 커밋(`a03ddd9`, `7276cc6`)이 추가한 backend의 `X-Request-Id` 헤더 전달 로직과 두 Python 엔진의 `CorrelationLogFilter`/`bind_job_id`/`bind_request_id`에 대해 어느 쪽에도 단위/통합 테스트가 없다. 기존 `AnalysisEngineClientReadinessTest`/`CircuitBreakerTest`류는 이 새 코드 경로를 전혀 건드리지 않는다.
- **왜 중요한가**: 상관관계 기능 자체의 목적(장애 시 세 서비스 로그를 하나의 ID로 추적)이 회귀해도 CI가 잡아주지 못한다.
- **근거**: 자체 리뷰 에이전트가 `grep -rl "bind_job_id\|bind_request_id\|X-Request-Id" **/tests`로 확인, 0건.

#### N7. [Low·컴플라이언스 문서화] 백업 보존(14일)·엔진 로그 무기한 보관이 개인정보처리방침에 고지되지 않음

- **문제**: `scripts/backup-mysql.sh`는 `BACKUP_RETENTION_DAYS`(기본 14일) 동안 사용자 이메일·비밀번호 해시가 담긴 MySQL 백업을 보관하고 MinIO로 원격 반출도 하지만, `PrivacyPage.jsx`는 백업을 전혀 언급하지 않는다. 또한 `logback-spring.xml`은 백엔드 로그를 30일로 시간 기반 만료시키지만, `analysis-engine`/`video-llm-engine`의 `RotatingFileHandler`는 크기 기반(100MB×10개)만 적용해 **시간 만료가 없다** — 로그 유입량이 적으면 jobId가 포함된 로그가 30일보다 훨씬 오래 남을 수 있는데, 이 역시 페이지에 고지되지 않는다.
- **왜 중요한가**: `PrivacyPage.jsx` 자체가 "법률 전문가 검토 전 초안"이라고 명시하고 있어 법적 결함은 아니지만, 사용자가 페이지만 읽고 "탈퇴하면 데이터가 완전히 사라진다"고 오해할 여지가 있다.
- **근거**: `scripts/backup-mysql.sh`(`BACKUP_RETENTION_DAYS=14` 기본값), `backend/src/main/resources/logback-spring.xml:38-45`(30일/1GB), `analysis-engine/app/core/logging_config.py:16-17` 및 동일한 `video-llm-engine` 파일(100MB×10, 시간 만료 없음), `frontend/src/pages/PrivacyPage.jsx` 전체 대조.

#### N8. [Low] nginx 엣지에 요청 속도 제한·server_tokens 은닉이 없음

- **문제**: `infra/nginx/nginx.conf`는 CSP·HSTS·X-Frame-Options 등 보안 헤더는 이미 탄탄하게 갖췄지만(`frame-ancestors 'none'`, `unsafe-inline` 없음 확인), `server_tokens off;`나 `limit_req`/`limit_conn` 존(zone)이 없다. 요청 속도 제한은 현재 backend의 bucket4j에만 있어, nginx 자체는 요청이 Spring까지 도달하기 전에 걸러주는 계층이 없다.
- **왜 중요한가**: `client_max_body_size 500m`(영상 업로드용, 의도된 값)와 결합하면 동시 대용량 업로드 몇 건만으로 nginx 워커 연결을 소모시키기 쉽다. 심각도는 낮지만 손쉽게 고칠 수 있는 항목이다.
- **근거**: `infra/nginx/nginx.conf` 전문 검토, `limit_req`/`limit_conn`/`server_tokens` grep 결과 0건.

#### N9. [Critical·데이터 무결성] `COMPLETED` 작업의 결과 JSON이 없거나 못 읽으면 조용히 "총점 0/UNKNOWN"으로 위장되고 이상 신호가 뜨지 않음 — 실제 라이브 데이터에서 재현됨

- **문제**: 관리자 계정으로 실제 서비스 사용자(`ehtkddn123@gmail.com`, userId=1)의 결과를 조회한 결과, `2조.MOV`/`4조.MOV`/`5조.MOV` 3건이 모두 `status: COMPLETED`인데 `scoreSummary`는 전 항목 0/`level:"-"`, `feedback`은 `generationMode:"UNKNOWN", overall:""`으로 완전히 비어 있었다. 그런데 프론트가 렌더링하는 `dataIssue`/`dataIssueDescription` 필드는 **둘 다 `null`**이라 사용자·관리자 어느 화면에도 "이 결과는 이상하다"는 신호가 전혀 뜨지 않는다.
- **근본 원인(코드로 직접 추적)**: `ResultQueryService.readFinalResultSafely()`(`backend/src/main/java/.../result/ResultQueryService.java:119-128`)가 `final_result.json`을 읽다가 어떤 `RuntimeException`이든 잡아서 로그도 안 남기고 조용히 빈 `Map.of()`를 반환한다. 그 빈 맵이 `ResultSummaryResponse.of(...)`에 들어가면 `createEmptyScoreSummary()`(125-137행, 전부 0/`"-"` 하드코딩)와 `createUnknownFeedback()`(139-149행, `"UNKNOWN"`/`""` 하드코딩)로 채워진다. 반면 `dataIssue`를 정하는 `toSummaryResponse()`(96-117행)는 **`UploadedVideo` row가 없는 경우(`MISSING_VIDEO`)만** 검사하고, "result JSON이 없거나 파싱 실패했다"는 케이스는 아예 `dataIssue` 값 자체가 코드에 정의돼 있지 않다(`ResultSummaryResponse.java` 전체에 `MISSING_VIDEO` 외 다른 값이 없음). 즉 저장소 문제로 결과 파일이 사라진 완료 작업이 "정상인데 점수만 낮은 결과"처럼 보이게 된다.
- **왜 중요한가**: 추측이 아니라 실제 운영 중인 계정의 실제 완료 작업(392MB/480MB/479MB 실제 영상 3건, 2026-07-08~07-14 사이 생성)에서 지금 이 순간 재현된다. 사용자 입장에서는 "분석은 됐는데 왜 다 0점이지"로만 보이고, 관리자 입장에서도 이상 배지가 없어 storage 계층 문제(로컬 fallback ↔ MinIO 이중화 과정에서의 파일 유실 가능성 등, 07-16/07-17 업데이트에서 다룬 이중 저장 구조와 연관 가능)를 알아챌 방법이 없다.
- **근거**: 관리자 API 실응답(`GET /api/admin/users/1/results?page=0`, 2026-07-18 실행 결과), `ResultQueryService.java:96-128`, `ResultSummaryResponse.java:55-149`, `ResultMergeService.java`(성공 경로는 `level`에 `"-"`를 절대 쓰지 않고 `EXCELLENT/GOOD/NORMAL/NEEDS_IMPROVEMENT`만, 실패 경로는 `"FAILED"`만 씀 — 관찰된 `"-"`는 두 경로 어디에서도 나올 수 없는 값이라 DTO 기본값임이 확정됨).
- **참고**: 단일 작업 상세 조회 `GET /api/results/{jobId}`(`ResultController`→`ResultQueryService.getFinalResult`)는 같은 상황에서 예외를 삼키지 않고 `BusinessException(FILE_NOT_FOUND)` → 404를 그대로 반환한다. 즉 목록(list) 경로와 상세(detail) 경로가 같은 장애를 서로 다르게 처리하고 있어, 목록에서는 "이상 없음"처럼 보이다가 상세를 열면 갑자기 404가 뜨는 사용자 경험 불일치도 함께 존재한다.

### 확인했지만 문제가 아니었던 것 (오탐 배제)

- `MinioCredentialsStartupValidator`/`OpenAiApiKeyStartupValidator`의 판정 로직 자체는 결함이 없다(정상 케이스 오탐 없음). 다만 docker-compose를 거치지 않고 dev 프로필을 바로 실행하며 `MINIO_ACCESS_KEY`/`MINIO_SECRET_KEY`를 안 채운 환경은 이제 기동이 막힌다 — 의도된 변경이지만 운영 채널에 공유가 필요하다.
- ffmpeg 120초 timeout은 backend의 더 긴 상위 timeout(10분 read-timeout, 20분 job soft-timeout, 30분 watchdog)보다 충분히 짧아 실제로 먼저 발동한다 — 무의미한 timeout이 아니다.
- `/results/:jobId`의 mock 모드 표기(`Mock 피드백`, `openai.enabled=false`, `Mock Video LLM 분석`)는 실사용 확인 결과 실제 결과처럼 위장하지 않고 정직하게 라벨링되어 있다 — 07-17 이전 우려("mock을 진짜로 오해할 위험")가 UI 차원에서는 이미 해소돼 있다.
- `npm audit --audit-level=high`, `video-llm-engine/requirements-test.txt` 감사는 현재 클린하다(0건).
- CSP는 `frame-ancestors 'none'`, `object-src 'none'`, `base-uri 'self'`, `form-action 'self'`를 포함하고 `unsafe-inline`/`unsafe-eval`이 없어 이미 탄탄하다.

### 관리자 페이지 후속 점검 (2026-07-18, 같은 날 추가)

07-18 최초 조사에서 비어 있던 "관리자 테스트 계정 부재" 항목을 직접 채웠다. 절차: 로컬 `.env`(git-ignored, 커밋 안 됨)에 `ADMIN_EMAILS=admin@example.com` 추가 → `docker compose up -d --wait backend`로 재기동 → `POST /api/auth/signup`으로 `admin@example.com`/`admin1234` 계정 생성(응답에 `"admin":true` 확인) → 실제 로그인해 `/admin`, `/admin/users/:userId`, `/admin/audit-logs` 점검.

- **이 절차 자체가 다음 순위 후보다**: 문서화된 시딩 스크립트나 절차가 없어 매번 이렇게 수동으로 `.env`를 고치고 backend를 재기동해야 한다. `docs/ops/`에 "로컬 관리자 계정 만들기" 한 단락을 추가하는 정도의 저비용 개선이다.
- `/admin` 대시보드·`/admin/users/:userId`(사용자별 결과 목록)·`/admin/audit-logs`는 데스크톱에서 콘솔 에러 없이 정상 렌더링됐다. 감사로그는 이번 세션에서 정지/강제탈퇴 등 파괴적 조치를 의도적으로 실행하지 않아 빈 상태로 확인됐다(정상적인 빈 상태 UI 자체는 문제없이 표시됨).
- **주의**: 사용자 목록에 실제 서비스 사용자 계정(프로젝트 소유자 본인 계정 포함)이 그대로 노출된다. 정지/강제탈퇴/결과삭제 버튼은 되돌리기 어려운 작업이라 이번 조사에서는 클릭하지 않았다 — 실사용 검증이 필요하면 별도의 격리된 테스트 사용자 계정으로 진행해야 한다.
- 이 과정에서 **N5(모바일 네비게이션 오버플로우)**가 관리자 계정에서 더 악화된다는 사실과, **N9(결과 데이터 이상 미검출)**가 실제 라이브 데이터에서 재현된다는 사실을 새로 확인해 각각 위에 반영했다.

### 여전히 점검 못 한 항목

- `/results/:jobId`의 실패(FAILED)·취소(CANCELLED) 상태 UI는 이번에도 성공 경로만 점검해 미확인.
- 실제(mock 아닌) OpenAI/Video LLM 응답 경로의 UI는 두 기능 모두 비활성 상태라 여전히 미점검.
- 관리자의 정지/강제탈퇴/결과삭제 조치가 실제로 감사로그에 정확히 기록되는지는, 파괴적 조치를 피하기 위해 이번에도 실행 확인을 하지 않았다.

### 다음 우선순위 (권장)

1. **N1(MinIO 삭제 누락)**, **N2(pillow CVE/CI)**, **N9(결과 데이터 이상 미검출)**가 가장 시급하다 — 세 건 모두 지금 실제로 재현되는 데이터/컴플라이언스/CI 문제이며 추측이 아니다.
2. N3(.env가 이미지에 복사될 수 있는 경로)은 `.dockerignore` 세 줄 추가로 끝나는 매우 저비용 고비용-임팩트 수정이라 바로 처리할 만하다.
3. N4(non-root 컨테이너), N5(모바일 네비게이션), N6(테스트 커버리지)은 각각 별도 규모의 작업이라 우선순위를 사용자와 다시 상의해 순서를 정하는 것이 좋다.

### 같은 날(2026-07-18) 후속 조치: N1·N2·N3·N4·N5·N6·N9 수정 완료

발견 직후 같은 회차에서 N1·N2·N3·N9(1·2순위)에 이어 N4·N5·N6도 순서대로 실제 수정·검증까지 마쳤다.

- **N4 해결**: `analysis-engine`/`video-llm-engine`/`infra/backup` Dockerfile에 non-root 사용자를 추가하고, `docker-compose.yml`의 backend/analysis-worker/analysis-engine/video-llm-engine에 `cap_drop: [ALL]` + `no-new-privileges`를 적용했다. frontend(nginx)는 포트 변경 없는 완전한 non-root 전환은 별도 과제로 남기고, `read_only: true` + tmpfs(캐시/pid/conf.d) + `cap_drop: [ALL]` + 포트 바인딩·워커 권한 하향에 필요한 `NET_BIND_SERVICE`/`CHOWN`/`SETUID`/`SETGID`만 되돌려주는 방식으로 하드닝했다. 실제 컨테이너 재기동으로 5개 서비스 모두 healthy 유지, 로그인→업로드→분석 실행→완료→삭제까지 전체 파이프라인이 정상 동작함을 확인했다. 이 과정에서 첫 시도 때 frontend가 `chown` 권한 부족으로 재시작 루프에 빠진 것과, backend/analysis-worker 이미지가 N1 커밋 이후 재빌드되지 않아 구버전 코드로 떠 있던 것(재검증 중 N1이 실제로는 반영 안 된 상태였음을 발견)을 실제 로그로 잡아 함께 고쳤다.
- **N5 해결**: 로그인 상태 헤더에 `lg`(1024px) 미만에서 접히는 햄버거 메뉴를 추가했다. 수정 중 데스크톱(1024~1280px)에서도 라벨이 줄바꿈되던 것을 추가로 발견해 `whitespace-nowrap`을 적용했다. 390/768/1023/1024/1280px 전 구간에서 `scrollWidth === clientWidth`(오버플로우 0)를 실측했고, 실제 클릭으로 메뉴 열기→링크 이동→자동 닫힘까지 확인했다.
- **N6 해결**: backend의 `X-Request-Id` 헤더 전달과 두 Python 엔진의 `bind_job_id`/`bind_request_id`/`CorrelationLogFilter`에 대한 자동화 테스트를 추가했다(`MockRestServiceServer` 기반 헤더 검증, contextvar 설정·복원 단위 테스트, `TestClient` 기반 종단 검증).
- **검증**: 모든 항목에서 backend 전체 테스트, analysis-engine pytest(97), video-llm-engine pytest(66), frontend lint/test(124)/build가 통과했고, N4/N5는 추가로 실제 Docker 컨테이너·브라우저 조작으로 확인했다.

### 같은 날(2026-07-18) 추가 후속 조치: N7·N8 수정 완료

- **N7 해결**: `PrivacyPage.jsx`에 "백업 보관"(`BACKUP_RETENTION_DAYS` 기본 14일, AES-256 암호화, 오브젝트 스토리지 반출 가능성, 삭제 이후에도 백업에는 남을 수 있음)과 "로그 보관" 절을 추가했다. 이후 두 Python 엔진 로그도 일 단위 순환 + 30일 보관으로 맞춰, 백엔드와 엔진 모두 시간 기준 로그 보관 상한을 갖게 했다. "보관 기간과 삭제" 절도 N1에서 고친 실제 동작(로컬+오브젝트 스토리지 사본 모두 삭제)에 맞춰 갱신했다.
- **N8 해결**: `infra/nginx/nginx.conf`에 `server_tokens off`와 IP 기준 `limit_req_zone`(30r/s, burst 60)/`limit_conn_zone`(20개)을 `/api/`와 `/` 위치에 적용했다. 모니터링 헬스체크(`/actuator/health`)는 제외했다. 실제 docker-compose 네트워크 안에서 `nginx -t` 통과, 실제 컨테이너를 띄워 정상 요청 200·`Server` 헤더 버전 없음·150개 동시 요청 중 56개가 실제로 503으로 차단되는 것까지 확인했다.
- **검증**: frontend lint/test(126)/build 통과, `docker-compose.yml + docker-compose.prod.yml` 오버레이 설정 검증 통과.

이로써 2026-07-18에 발견한 N1~N9 전 항목(N1~N6, N9는 이전 절에서 완료 기록, N7·N8은 위)이 모두 수정·검증됐다.

- **N1 해결**: `ResultCommandService.deleteResult()`가 이제 로컬 삭제와 함께 `objectStorage.deleteObjectsWithPrefix("uploads/{jobId}/")`, `deleteObjectsWithPrefix("results/{jobId}/")`를 호출한다(`StorageCleanupService`/`OriginalVideoRetentionService`와 동일한 best-effort 패턴 — MinIO 호출이 실패해도 로컬 삭제·DB 삭제는 막지 않음). MinIO 실패 시에도 삭제가 정상 진행되는지, 정상 시 두 prefix가 정확히 호출되는지 각각 단위 테스트로 고정했다.
- **N2 해결**: `analysis-engine/requirements.txt`와 `video-llm-engine/requirements-real-model.txt`의 `pillow`를 `12.2.0`→`12.3.0`으로, `requirements-real-model.txt`의 `setuptools`를 `81.0.0`→`83.0.0`, `torch`를 `2.12.1`→`2.13.0`으로 올려 발견된 CVE 8+1+1건을 전부 해소했다(`pip-audit` 재실행으로 클린 확인). `requirements-real-model.txt`가 CI 감사 사각지대였던 부가 발견도 함께 닫았다 — `.github/workflows/verify.yml`의 `python-engines` 잡에 `pip-audit --no-deps --disable-pip -r requirements-real-model.txt` 스텝을 추가했다(무거운 torch 설치 없이 핀 고정 버전만 감사하므로 CI 비용 증가가 거의 없음). 로컬에서 동일 명령으로 실제 클린 통과를 확인했다.
- **N3 해결**: `analysis-engine/.dockerignore`, `video-llm-engine/.dockerignore`, `frontend/.dockerignore`에 `.env`를 추가했다. `.env.example`은 제외 대상이 아니라 그대로 포함된다(플레이스홀더만 있어 안전). 실제로 `video-llm-engine`을 다시 빌드해 이미지 안에 `.env.example`만 있고 진짜 `.env`(NVIDIA 키 포함)는 전혀 들어가지 않음을 확인했다.
- **N9 해결**: `ResultQueryService.toSummaryResponse()`가 이제 `analysisJob.getStatus() == COMPLETED && finalResult.isEmpty()`인 경우를 새 손상 유형 `dataIssue=RESULT_DATA_UNAVAILABLE`로 표시한다(`ResultSummaryResponse.missingResultData(...)` 신규 팩토리). QUEUED/RUNNING처럼 아직 결과 파일이 없는 게 정상인 상태는 그대로 두어 오탐을 만들지 않는다. `readFinalResultSafely()`의 예외 삼킴에도 `log.warn`을 추가해 운영 관측성을 확보했다. "완료인데 결과 파일 없음"과 "아직 대기 중이라 결과 없음"을 각각 재현하는 단위 테스트 2건을 추가했다. 프론트는 `dataIssue`/`dataIssueDescription`을 값에 상관없이 그대로 렌더링하는 기존 구조라 별도 프론트 수정이 필요 없었다.
- **검증**: backend 전체 테스트(`./gradlew test`, `BUILD SUCCESSFUL`), analysis-engine pytest(91) 및 pip-audit(0건), video-llm-engine pytest(58) 및 `requirements-real-model.txt` pip-audit(0건), `analysis-engine`/`video-llm-engine` Docker 이미지 실제 재빌드(pillow 12.3.0 import 확인, `.env` 미포함 확인) 모두 통과.
- **남은 것**: 이번 수정으로 N1이 향후 발생하는 삭제/탈퇴에는 적용되지만, 07-16/07-17 업데이트에서 지적된 "기존에 이미 유실된 MinIO 오브젝트"나 이번에 N9로 발견한 실제 3건의 손상된 결과(userId=1)에 대한 **소급 정리는 포함하지 않는다** — 별도로 결정·진행할 사안이다.

### 2026-07-20 재확인: N9가 언급한 userId=1의 3건은 현재 정상 상태

`GET /api/admin/users/1/results`로 `2조.MOV`(20260714091707-1463466a)/`4조.MOV`
(20260714041149-c5fdc530)/`5조.MOV`(20260708022538-b221e530) 3건을 다시 조회한
결과, 셋 다 `dataIssue: null`이고 실제 점수(totalScore 47/55/54)와 실제 피드백
텍스트가 정상적으로 채워져 있었다. 로컬 디스크(`storage/results/{jobId}/`)에는
파일이 없었지만(정리 스케줄러가 지운 것으로 보임), MinIO(`hanium-storage/results/
{jobId}/`)에는 6개 결과 파일(final-result.json 포함)이 모두 온전히 남아 있고
`readFinalResultSafely()`가 MinIO 우선 읽기로 정상적으로 서빙하고 있음을 확인했다.

즉 이 3건은 "손상된 채로 방치된 데이터"가 아니라, 07-18 관찰 시점 이후 어느
시점(P2 MinIO 백필 관련 작업으로 추정, 파일 mtime이 07-16)에 MinIO 쪽 사본이
채워지면서 이미 정상화되어 있었다. 별도의 소급 정리 작업은 필요하지 않다 —
위 "남은 것" 항목의 이 부분은 완료된 것으로 정정한다. (다른 계정에 동일한
근본 원인의 미확인 사례가 남아있을 가능성 자체는 배제하지 않았다 — 그건
전수조사가 필요한 별개 사안이다.)

---

## 업데이트: 2026-07-21 현재 상태

2026-07-20 이후 두 가지를 반영한다: (1) Video LLM 실연동이 결정 문서 단계를 넘어 실제로
활성화·검증됐다는 사실, (2) 이번 회차의 2차 코드 리뷰(4개 컴포넌트 병렬 검토)에서 새로
발견해 같은 회차에 전부 수정·테스트·커밋까지 마친 14건. 이번 회차는 새 코드 수정에 앞서
의존성 재감사와 CI 관련 사각지대 재확인도 함께 수행했다.

### L8(Video LLM) 판정 상향: 부분해결 → 해결

- `video-llm-engine/.env`, 루트 `.env` 모두 `VIDEO_LLM_ENABLED=true`, `VIDEO_LLM_BACKEND=external-api`로 확인됨.
- `docs/service-plan/video-llm-activation-decision.md`의 2026-07-20 갱신에 실제 NVIDIA API 키로
  전환해 `readiness`(`mode: REAL, realModelReady: true`)와 실제 업로드→분석 완료 E2E까지 확인한
  기록이 있다. A-4(업로드 동의 체크박스)는 "미구현"이 아니라 "PrivacyPage 국외이전 고지로 대체하기로
  결정"으로 판정이 바뀌었다.
- 따라서 서비스 가능 수준 L1~L10 기준 중 마지막까지 "결정 대기"였던 L8이 이제 실질적으로 충족 상태다.
  L1~L10 전체를 다시 보면 L5(진짜 메시지 브로커가 아닌 DB 폴링 claim 큐)만 "구조적 한계로 의도적 유지"
  상태로 남는다.

### 2차 코드 리뷰로 새로 발견해 같은 회차에 수정한 14건 (커밋 `9c436d8`~`f80d65e`)

기존 롤링 문서의 A/B/N 계열 항목과는 별개로, 이번 회차는 1차 9건 수정 이후 재점검에서
추가로 나온 항목이다. 전부 코드 근거 확인 → 구현 → 회귀 테스트로 결함 재현/해결 확인 →
전체 스위트 통과 → 파일별/논리 단위로 분리 커밋까지 마쳤다.

| # | 항목 | 심각도 | 요지 |
| --- | --- | --- | --- |
| 1 | 회원탈퇴/결과삭제 파일 삭제 트랜잭션 원자성 | High | `ResultCommandService.deleteResult()`가 DB 삭제 전에 로컬/오브젝트 삭제를 수행해, DB 트랜잭션이 롤백돼도 파일은 이미 지워지는 비원자성이 있었다. `TransactionSynchronization.afterCommit()`으로 커밋 후에만 파일을 지우도록 수정. |
| 2 | CoachChatService가 OpenAI 동기 호출 동안 DB 트랜잭션을 점유 | High | `@Transactional` 메서드 안에서 OpenAI 코치 응답을 기다리는 동안 DB 커넥션을 계속 물고 있어, 커넥션 풀 고갈 위험이 있었다. `TransactionTemplate`로 트랜잭션을 앞/뒤 두 개로 쪼개 외부 호출 중에는 커넥션을 반납하도록 수정. |
| 3 | rate limit INCR+EXPIRE 비원자성 | Medium | Redis INCR 성공 직후 EXPIRE만 실패하면 카운터가 TTL 없이 영구 잔류하는 경합이 있었다. Lua 스크립트로 원자화. |
| 4 | Video LLM 일일/월간 한도의 불필요한 소비 순서 | Low | 일일 한도를 먼저 tryConsume한 뒤 월간 한도가 소진돼 있으면, 일일 예산만 헛되이 소비되고 어차피 호출은 건너뛰었다. `wouldAllow`로 두 한도를 모두 peek한 뒤 통과 시에만 소비하도록 변경. |
| 5 | analysis-engine 프로젝트 루트 경로 계산 버그 | High | `model_registry.py`의 자체 `resolve_project_root()`가 `parents` 루프에서 변수를 갱신하지 않는 버그로, 실제 컨테이너 레이아웃(cwd=`/app`)에서 절대 매치되지 않고 잘못된 경로(`/app/app/core`)로 폴백하고 있었다. `basic_analysis.py`의 올바른 구현을 `app/core/paths.py`로 추출해 공유. |
| 6 | 양쪽 눈 시선 측정 실패 시 정면 응시로 오인 | Medium | `average_gaze_ratios`가 두 눈 모두 실패 시 `(0.5, 0.5)`(완벽한 정면 응시)를 반환해 측정 불가를 최고 점수로 오인시켰다. `None` 반환 후 해당 프레임을 집계에서 제외하도록 수정. |
| 7 | 얼굴 미검출 시 중립 표정으로 오인 | Medium | `resolve_dominant_emotion`의 `filtered_counts`는 항상 4개 키가 0으로 채워져 있어 "비어 있음" 검사가 절대 참이 되지 않고, `max()`가 삽입 순서상 첫 키인 neutral을 반환했다. "값이 전부 0인지" 검사로 수정. |
| 8 | NVIDIA 응답의 NaN/Infinity 미검증 | Medium | `json.loads()`가 표준 밖 확장 리터럴(NaN/Infinity)을 기본 허용하는데, 이 값은 모든 비교에서 항상 False가 되어 상/하한 검사를 그대로 통과했다. `math.isfinite()` 명시 검증 추가. |
| 9 | 순서 뒤집힌 음수 타임스탬프가 클램프 후 정상으로 위장 | Medium | startSec/endSec이 둘 다 음수이며 순서가 뒤집힌 경우, 각각 0으로 클램프하면 0<0이 되어 정상처럼 보였다. 클램프 전 원본 값으로 순서 검증하도록 순서 변경. |
| 10 | videoPath 경로 이탈 방지 없음 | High(방어적) | video-llm-engine이 `request.videoPath`를 검증 없이 그대로 열어 제3자(NVIDIA)로 업로드했다. `VIDEO_LLM_ALLOWED_VIDEO_BASE_DIR` 밖 경로를 거부하는 검증 추가(analysis-engine의 기존 job_id 검증과 동일한 방어 목적). |
| 11 | videoDownloadUrl SSRF 미검증 | High(방어적) | 요청마다 달라지는 `videoDownloadUrl`(주로 backend가 만드는 MinIO presigned URL)에는 `resolve_absolute_http_url` 같은 scheme/host 검증이 없었다. `file://` 등 예상 밖 스킴을 거부하도록 추가. |
| 12 | 폴링 중 결과 페이지 이동 가능 | Low(UX) | 분석 결과 파일이 아직 없는 폴링 중에도 "결과 페이지로 이동" 버튼이 활성 상태라 이동 시 깨진 에러 화면을 볼 수 있었다. 폴링 중 비활성화. |
| 13 | 파일 거부 후 같은 파일 재선택 시 무반응 | Low(UX) | `<input type="file">`는 같은 경로 파일을 다시 선택해도 파일 목록이 안 바뀌었다고 보아 change 이벤트가 발생하지 않는데, 이전에는 성공 시에만 value를 초기화했다. 거부 경로에서도 초기화하도록 수정. |
| 14 | 결과 상세 페이지가 상태값을 원본 enum으로 노출 | Low(UX) | `ResultDetailPage`가 `statusDescription`에 `status`를 그대로 복사해 "QUEUED" 같은 원본 값을 사용자에게 노출했다. UploadPage의 한글 라벨 맵을 `constants/analysisStatus.js`로 추출해 공유. |

**검증**: 각 항목마다 결함 재현(수정 되돌려 실패 확인) → 수정 → 회귀 테스트 추가 → 해당
컴포넌트 전체 스위트 통과를 개별 확인했고, 최종적으로 backend 전체(`./gradlew test`),
analysis-engine(118 passed), video-llm-engine(130 passed), frontend(185 passed)를 전부
재실행해 통과를 재확인했다. 커밋은 파일/논리 단위로 14개로 분리했다(`git log 9c436d8..f80d65e`).

### 의존성 재감사 (2026-07-21, 새 코드 수정 없이 확인만)

| 대상 | 결과 |
| --- | --- |
| `analysis-engine/requirements.txt` (`pip-audit`) | 0건 (N2에서 pillow 12.3.0으로 이미 해결된 상태 유지 확인) |
| `video-llm-engine/requirements-test.txt` (`pip-audit`) | 0건 |
| `video-llm-engine/requirements-real-model.txt` (`pip-audit --no-deps`) | 0건 (N2 부가 발견분도 유지 확인) |
| `frontend` (`npm audit --audit-level=high`) | 0건 |

새로 잡힌 CVE는 없다. N2에서 CI에 추가한 `requirements-real-model.txt` 감사 스텝도 여전히
`.github/workflows/verify.yml`의 `python-engines` job에 남아 있음을 확인했다.

### 2026-07-21 기준 가장 큰 잔여 리스크 (우선순위 순)

1. **"외부 환경" 항목은 여전히 로컬 개발 환경에서는 검증 불가능하다.** staging 기존 데이터 백필,
   MinIO 강제 중단 시 동작 확인, 실제 운영 CPU/GPU 부하 측정은 실제 staging/운영 인프라가 있어야
   진행 가능하며 이번 회차에도 진행하지 않았다(07-17 업데이트의 "남은 외부 환경 항목"과 동일).
2. **로컬 디스크 fallback 제거 여부가 여전히 미결정이다.** MinIO 이중 쓰기 구조 자체는 유지되고
   있고, 안전망을 언제 걷어낼지는 별도 결정 사안으로 남아 있다(07-16 P2와 동일).
3. **L5(DB 폴링 claim 큐)는 의도된 구조적 한계로 유지된다.** 진짜 메시지 브로커(우선순위/데드레터/
   지수 백오프)가 필요해지는 트래픽 규모에 도달하기 전까지는 현재 구조로 충분하다는 기존 판단을
   유지한다.
4. **Video LLM이 이제 진짜로 켜져 있으므로, 비용/폴백 관측을 실사용 기준으로 계속 지켜봐야 한다.**
   `video_llm_generation_total{mode=...}` 메트릭과 `VideoLlmFallbackRateHigh` 알림은 이미 구현돼
   있지만, 실제 트래픽에서 폴백률이나 월 예산 소진 속도를 관찰한 이력은 아직 짧다.
5. **이번 14건 중 일부(#10, #11)는 "backend가 항상 안전한 값만 보낸다"는 전제 위의 방어적 조치다.**
   즉 지금 당장 악용 가능한 경로가 확인된 것은 아니고, 백엔드 버그·침해 시 2차 피해를 줄이는
   심층 방어다 — 우선순위를 매길 때 "이미 뚫린 취약점"과 구분해서 봐야 한다.

### 다음 우선순위 (권장)

이번 회차로 코드 수준의 새 발견은 대부분 소진됐다. 다음으로 의미 있는 진전은 아래 셋 중
하나를 사용자와 상의해 정하는 것을 권장한다.

1. **로컬 디스크 fallback 제거 결정** — MinIO 백필이 이미 실환경에서 검증된 만큼(07-17), 쓰기
   실패 시 정책을 "에러로 전환"할지 결정하면 L4/B4 계열 갭이 완전히 닫힌다.
2. **staging 환경 구축 후 "외부 환경 항목" 전수 실행** — 지금까지 코드/로컬 Docker로는 검증할
   수 없었던 항목들(기존 데이터 백필, 강제 중단 시나리오, 실부하)을 실제로 닫는 단계.
3. **Video LLM 실사용 관측 대시보드를 일정 기간 관찰** — 새 코드 수정보다는 운영 관찰 기간을
   가지며 비용/폴백률 실측치를 쌓는 단계.

---

## 업데이트: 2026-07-21 (같은 날 후속) 로컬 디스크 fallback 제거 결정 완료

위 "다음 우선순위 1번"을 바로 이어서 진행했다. 결론부터: **결정은 이미 07-16/07-17에 내려져
있었다**(local/dev=A안 유지, prod=B안 적용) — 이번 회차는 그 결정이 실제로 코드대로 동작하는지
아직 실측하지 않았던 마지막 체크리스트 항목("MinIO 강제 중단 시 업로드가 명확한 에러로 실패하는지")을
로컬 Docker 환경에서 실제로 검증해 "미결정"이라는 표현을 "실측 완료"로 정정하는 작업이었다.

### 실행한 검증

로컬 docker-compose 스택을 prod와 동일한 정책값(`STORAGE_OBJECT_WRITE_REQUIRED=true`,
`STORAGE_OBJECT_READ_PREFERRED=true`)으로 기동하고, 전용 테스트 계정으로 실제 HTTP API를
호출해 3단계로 검증했다: (1) MinIO 정상 시 업로드 성공 + 로컬·MinIO 양쪽 미러링 확인, (2) MinIO를
`docker compose stop`으로 강제 중단 후 업로드 시도 → **500 `FILE_UPLOAD_FAILED`로 명확히 실패**,
로컬 파일도 정리되고 DB에도 행이 안 남음(트랜잭션 롤백)을 직접 확인, (3) MinIO 복구 후 업로드가
코드 변경 없이 즉시 재개됨을 확인. 부가로 회원탈퇴 시 두 테스트 업로드의 로컬/MinIO/DB가 전부
삭제됨도 함께 확인했다(이번 세션 앞부분에서 고친 파일삭제 트랜잭션 원자성 수정, 커밋 `9c436d8`이
strict 모드에서도 정상 동작함을 재확인). 상세 절차와 근거는 `docs/ops/minio-backfill-and-fallback-plan.md`의
"2026-07-21 로컬 환경 MinIO 강제 중단 실측" 절에 있다. 검증에 사용한 컨테이너는 종료 후 전부 원래
상태(정지)로 되돌렸고, `.env`나 컴포지트 파일은 수정하지 않았다(환경변수 오버라이드만 사용).

### 최종 결정

- **local/dev(A안)·prod(B안) 이원화를 그대로 확정.** 더 이상 "미결정" 항목이 아니다.
- **C안(로컬 완전 제거)은 계속 보류.** 트리거 조건을 명시했다 — MinIO가 단일 인스턴스(SPOF)가
  아니게 되는 시점(분산 모드 또는 관리형 오브젝트 스토리지 전환)에 재검토한다.
- staging 기존 데이터 백필만 외부 인프라가 필요해 여전히 대기 상태로 남는다.

이로써 위 "2026-07-21 기준 가장 큰 잔여 리스크"의 2번 항목은 해소됐다. 잔여 리스크 목록에서
제거하고 1/3/4/5번만 유효한 것으로 갱신한다.

---

## 업데이트: 2026-07-21 (같은 날 후속 2) "외부 환경 항목" 로컬 시뮬레이션 진행

실제 staging 서버/클라우드 계정이 아직 없어(사용자 확인), 같은 노트북에서 실제 docker-compose
스택을 prod에 가깝게 기동해 "외부 환경 항목" 중 로컬로 검증 가능한 부분만 진행했다.

### 로컬 파일 백필 실측

이 저장소의 `storage/uploads`·`storage/results`에 실제로 남아 있던 로컬 파일을 대상으로
`docker compose run --rm -e STORAGE_BACKFILL_ENABLED=true backend`를 실행했다(env를 셸
프리픽스로 주지 말고 `-e` 플래그로 직접 주입해야 컨테이너에 실제로 전달된다는 점을 시행착오로
확인 — `docker-compose.yml`에는 `STORAGE_BACKFILL_ENABLED`가 배선돼 있지 않아 셸 프리픽스만으로는
전달되지 않는다). 결과: `uploads[scanned=60, uploaded=42, skipped=18, failed=0]`,
`results[scanned=106, uploaded=3, skipped=103, failed=0]`. MinIO 오브젝트 수(uploads 87→129,
results 134→137)가 정확히 델타만큼 증가함을 `mc ls`로 직접 대조했다. 재실행 시
`uploaded=0, skipped=전체, failed=0`으로 idempotency도 확인했다. `docs/ops/minio-backfill-and-fallback-plan.md`의
"파트 1" 절차가 실제로 이 저장소의 로컬 데이터에 대해 정상 동작함을 최초로 실측했다는 의미가 있다
(이전까지는 일회용 격리 MinIO의 인공 테스트 파일 2개로만 검증됐었다).

### k6 부하 테스트 스크립트의 실제 버그 3건 발견·수정 (커밋 `418c801`)

실제로 `scripts/load-test/upload-analyze.js`를 이 저장소의 실제 docker-compose 스택에 대해
반복 실행하는 과정에서, 스크립트 자체의 결함 3건을 발견해 즉시 고쳤다:

1. **`useVideoLlm: true` 고정** — Video LLM이 실제로 켜져 있는(`VIDEO_LLM_ENABLED=true` +
   실제 API 키) 이 환경에서 VU를 올려 돌리면, 부하 테스트의 목적과 무관하게 실제 유료 NVIDIA
   호출이 동시다발적으로 발생하는 상태였다. 기본값을 `false`로 바꾸고 `USE_VIDEO_LLM=true`로만
   opt-in하도록 수정.
2. **`gracefulStop: "30s"` 고정** — `MAX_POLL_SECONDS`(기본 180초)보다 훨씬 짧아, 실제로
   VUS=3짜리 최초 smoke 실행에서 "0 complete, N interrupted"로 관측됐다. 서버는 실제로
   정상 처리 중이었는데(DB 확인으로 검증) 테스트 하네스 자체의 타이밍 버그 때문에 마치 아무
   것도 안 끝난 것처럼 보이는 착시였다. `MAX_POLL_SECONDS + 30초`로 자동 계산하도록 수정.
3. **매 반복(iteration)마다 재로그인** — VUS=4~8로 실제로 돌려본 결과, 로그인 rate limit
   (이메일 기준, 기본 5/10분)이 몇 초 안에 소진되어 "login 200" 체크가 최대 97.76% 실패로
   나타났다. 이 상태에서는 겉보기엔 "부하 테스트를 실행했다"처럼 보이지만 실제로는 인증
   계층에서 대부분 막혀 분석 파이프라인에는 거의 부하가 가지 않는다 — 07-20 노트에 남아 있던
   "테스트 진행을 위해 Redis rate-limit 키를 수동 삭제해야 했다"는 워크어라운드의 근본 원인이
   바로 이것이었다. VU당 최초 1회만 로그인하도록 고쳐 해결했다.

### 첫 정식(제한적) 부하 실행

세 버그를 고친 뒤 `analysis-worker`를 반드시 함께 띄워야 한다는 것도 실측으로 확인했다
(이 compose 토폴로지는 기본적으로 `backend`=API 전용, 실행은 `analysis-worker`가
전담 — `ANALYSIS_DISPATCH_LOCAL_ON_RUN`이 backend에서는 기본 `false`다. 이 사실을 모르고
`backend`만 띄운 채 첫 테스트를 돌렸다가 job이 전부 `QUEUED`에 멈춰 있는 것을 보고서야
발견했다). `VUS=4 RAMP_UP=10s HOLD=90s MAX_POLL_SECONDS=150`(VU 4는 `analysis-worker`의
기본 `ANALYSIS_EXECUTOR_MAX_POOL_SIZE=4`와 정확히 같은 경계값)로 실행한 결과
`http_req_failed=0.00%`, `analysis_time_to_complete_seconds` 평균 약 74초(useVideoLlm/
useOpenAi 모두 false, 실제 ffmpeg/faster-whisper/MediaPipe로 처리).

### 남은 것 — VUS 5+ 부하 측정은 별개의 rate limit 벽에 막혀 미완료

VUS를 5~10으로 올려 큐잉/백프레셔가 실제로 발동하는 지점을 찾으려 했으나, 로그인(이메일
기준)·가입(클라이언트 IP 기준) rate limit 용량이 각각 기본 5/10분이라 `AUTO_SIGNUP=true`로도
동일 IP에서 도네이션하는 이 실행 방식으로는 인증 계층에서 먼저 막혔다. `docker-compose.yml`에
`LOGIN_RATE_LIMIT_CAPACITY`/`SIGNUP_RATE_LIMIT_CAPACITY`를 오버라이드할 env 배선이 없어
이번 회차에는 완화하지 못했다(대신 Redis rate-limit 키를 직접 삭제하는 임시 방편을 시도했으나,
재시도가 반복되며 곧바로 재소진돼 근본 해결은 아니었다). 상세 내용과 다음 시도를 위한 선택지
세 가지(compose에 env 노출 / 계정 사전 생성 / 테스트 직전 Redis 키 삭제)는
`scripts/load-test/README.md`의 "2026-07-21 VUS 5+ 시도에서 발견한 별개의 차단 요인" 절에
기록했다. **VUS 5+ 정식 처리량 리포트(p50/p95, 큐잉 발동 지점)는 이번 회차에도 완성하지 못한
과제로 남는다.**

검증에 사용한 테스트 계정 20개는 전부 회원탈퇴 API로 정리했고(로컬/MinIO/DB 전부 삭제 확인),
사용한 컨테이너는 전부 원래 상태(정지)로 되돌렸다.

### 다음 우선순위 갱신

1. **VUS 5+ 부하 리포트 완성** — 위 세 가지 선택지 중 하나로 rate limit 벽을 우회한 뒤,
   실제 큐잉/백프레셔 발동 지점과 p50/p95 완료 시간을 측정하는 것이 이제 가장 구체적이고
   비용이 낮은 다음 단계다.
2. **staging 인프라 구축** — 여전히 사용자의 실제 서버/클라우드 계정 결정이 필요한, 로컬로는
   대체할 수 없는 유일한 항목(기존 데이터 백필의 "다른 사람의 실제 운영 데이터" 시나리오,
   여러 대의 물리적으로 분리된 인스턴스 간 네트워크 장애 시나리오 등).
3. **Video LLM 실사용 관측을 계속 지켜보기** — 이전 업데이트와 동일하게 유효.

---

## 업데이트: 2026-07-21 (같은 날 후속 3) VUS 5+ 부하 리포트 완성 — 신규 Critical 발견

위 "다음 우선순위 1번"을 완료했다. `docker-compose.yml`에 누락돼 있던
`LOGIN_RATE_LIMIT_CAPACITY`/`LOGIN_IP_RATE_LIMIT_CAPACITY`/`SIGNUP_RATE_LIMIT_CAPACITY`
env 배선을 추가해(다른 rate limit 카테고리는 이미 노출돼 있었는데 이 셋만 빠져 있었음) 테스트
세션 한정으로 값을 올려 rate limit 벽을 해결했고, k6 스크립트의 두 번째 실제 버그(`gracefulRampDown`
누락으로 VUS=8+에서 정상 처리 중인 반복도 전부 "interrupted"로 오판되던 문제)도 고쳐 VUS=4/8/16
세 단계로 정식 부하 테스트를 완료했다.

### 신규 발견 — [High~Critical] analysis-engine 서킷브레이커가 부하 아래에서 실제로 열리며, 큐에 있던 다수의 정상 요청이 한꺼번에 실패로 전환된다

- VUS=4(8/8 성공, 평균 74s) → VUS=8(1차 8/8, 2차 5/8 실패) → VUS=16(6/16 성공, 10/16 실패)로
  갈수록 실패 건수가 늘었다. 완료 시간도 74s→106s→169s로 늘어 `analysis-worker`의
  `MAX_POOL_SIZE=4`를 넘는 큐잉이 지연을 유발하는 것 자체는 의도된 백프레셔로 정상이다.
- 문제는 **실패 방식**이다: `AnalysisEngineClient`의 서킷브레이커(`sliding-window-size=10`,
  `failure-rate-threshold=50%`)가 열리면, 큐에서 대기 중이던 작업들이 실제 analysis-engine
  호출을 시도조차 하지 못하고 `CallNotPermittedException`으로 수 ms 안에 한꺼번에 `FAILED`
  처리된다. VUS=16에서 마지막 10건이 정확히 같은 타임스탬프에 함께 실패한 것으로 이를
  직접 확인했다. 서킷브레이커가 "느린 처리량"을 "일괄 실패"로 증폭시키는 것이다.
- **근본 원인은 이번 회차에도 확정하지 못했다.** `analyze()`의 catch 블록은 원본 예외를
  `기본 분석 엔진 호출에 실패했습니다: <메시지>` 형태로 남기는 코드 경로가 있는데도, 이
  세션 전체 analysis-worker 로그에서 이 문자열이 단 한 번도 나타나지 않았다(있는 건 이미
  서킷이 열린 뒤의 `CallNotPermittedException` 메시지뿐). analysis-engine 자체 접근 로그도
  200 20건/401 2건뿐이고, 그 401 2건은 `analysis-worker`(172.18.0.8)가 아니라 도커 게이트웨이
  IP(172.18.0.1 — `analysis-engine` 포트가 `127.0.0.1:8001`로 호스트에 퍼블리시돼 있어 호스트
  에서 직접 호출 가능)에서 왔고 양적으로도 서킷을 열기엔 부족해, 실제 부하 트래픽과 무관한
  산발적 호출로 보인다. 즉 **서킷을 최초로 연 클라이언트 측 예외의 정체가 여전히 미확인**이다.
- **왜 이게 중요한가**: 지금 상태로는 동시 사용자가 늘어날 때(analysis-worker 인스턴스 1개
  기준 대략 VUS 8 부근부터), 시스템이 "느려지다가 서서히 막히는" 것이 아니라 "멀쩡하던 대기
  작업들이 갑자기 무더기로 실패"하는 방식으로 저하된다. 사용자 경험상으로는 분석이 왜
  실패했는지 알 수 없는 채로 실패 통보를 받게 된다.
- 상세 데이터와 표는 `scripts/load-test/README.md`의 "2026-07-21 VUS=4/8/16 정식 부하
  측정 결과" 절에 있다.

### 다음 우선순위 재갱신

1. **서킷브레이커를 최초로 여는 근본 원인 확정** — `analyze()`의 catch 블록에 원본 예외의
   클래스명을 로그로 남기거나, DEBUG 레벨로 재현하거나, resilience4j 이벤트 리스너(상태
   전이 로그)를 추가해 다음 재현 시 확실히 잡아야 한다. 근본 원인 없이 서킷브레이커 임계치나
   analysis-engine 모델 풀/CPU 제한만 늘리는 것은 증상만 가릴 위험이 있다.
2. **staging 인프라 구축** — 이전 업데이트와 동일하게 유효.
3. **Video LLM 실사용 관측을 계속 지켜보기** — 이전 업데이트와 동일하게 유효.
