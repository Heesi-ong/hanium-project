# CSRF threat model for cookie JWT auth

작성일: 2026-07-12
최종 갱신: 2026-08-12

## Context

프론트엔드는 JWT를 `localStorage`에 저장하지 않고 `access_token` HttpOnly 쿠키로 인증한다. 쿠키 속성은 `SameSite=Lax`, `HttpOnly`, `Path=/`이고, local/dev HTTP 환경에서는 `Secure=false`, prod 기본값은 `Secure=true`이다. 별도 CSRF 토큰 대신 cookie 인증 상태 변경 요청에 전용 Origin/Fetch Metadata 검증을 적용한다.

이 문서는 현재 API가 SameSite 쿠키와 CORS만으로 브라우저 기반 CSRF에 충분히 방어되는지 확인하기 위한 위협모델이다.

## Current browser-side assumptions

- `SameSite=Lax` 쿠키는 교차 사이트 `fetch`/XHR 및 교차 사이트 POST form 제출에 자동 첨부되지 않는다. top-level safe navigation(GET 등)에는 첨부될 수 있지만, 현재 상태 변경 API는 GET으로 열려 있지 않다.
- Spring CORS 설정은 `/api/**`에 대해 `cors.allowed-origins`에 있는 origin만 허용하고 `allowCredentials(true)`를 사용한다.
- 기본 허용 origin은 `http://localhost:5173,http://127.0.0.1:5173`이다. 임의 origin의 preflight와 Origin-bearing simple POST는 로컬 검증에서 `403 Invalid CORS request`로 차단됐다.
- CORS는 응답 읽기 제어이므로 그 자체를 CSRF 방어로 간주하지 않는다. `CookieOriginProtectionFilter`가 cookie 인증의 unsafe method(`POST`, `PUT`, `PATCH`, `DELETE`)를 별도로 검사한다.
- `Origin`이 있으면 정확한 allow-list 일치만 허용한다. `Origin`이 없더라도 브라우저 요청은 `Sec-Fetch-Site: same-origin`만 허용하며 `same-site`, `cross-site`, `none`은 거부한다. Fetch Metadata가 없지만 `Mozilla/` User-Agent인 브라우저 요청도 거부한다.
- 명시적 Bearer 인증은 브라우저 자동 첨부 자격증명이 아니므로 이 필터 대상에서 제외한다. Fetch Metadata와 브라우저 User-Agent가 모두 없는 비브라우저 호출도 기존 운영 스크립트 계약을 유지한다.

## State-changing endpoints

| Endpoint | Handler | Body / content type | HTML form triggerability | Auth required by `SecurityConfig` | Arbitrary-origin fetch/CORS result | CSRF assessment |
| --- | --- | --- | --- | --- | --- | --- |
| `POST /api/auth/signup` | `AuthController.signup` | `@RequestBody SignupRequest`; intended JSON | Plain form is not a valid JSON request. Origin-bearing form is rejected by CORS. | No (`permitAll`) | Preflight/simple request from unallowed origin rejected | Login-free account creation is not cookie-auth CSRF. Keep rate limiting. |
| `POST /api/auth/login` | `AuthController.login` | `@RequestBody AuthRequest`; intended JSON | Plain form is not valid JSON. No-Origin form-urlencoded is covered by integration tests and returns 415; Origin-bearing form is rejected by CORS. | No (`permitAll`) | Preflight/simple request from unallowed origin rejected | JSON login CSRF is blocked by preflight. Wrong content type is handled as a 4xx client error, not a 500. |
| `POST /api/auth/logout` | `AuthController.logout` | No body required | Shape is form-submit compatible. Origin-bearing form is rejected by CORS. | No (`permitAll`) | Unallowed Origin과 브라우저 Origin 누락은 403 | 인증 쿠키가 있으면 전용 Origin/Fetch Metadata 필터를 통과해야 한다. 쿠키 없는 익명 호출은 만료 쿠키만 반환하므로 사용자 인증 상태를 서버에서 변경하지 않는다. |
| `POST /api/analysis/upload` | `AnalysisController.uploadVideo` | `multipart/form-data` with `file` part | Form-submit compatible if attacker can choose a file field, but cross-site cookies should not be sent with `SameSite=Lax`. | Yes (`/api/**.authenticated`) | Unallowed origin rejected | Browser CSRF risk is low due to auth + SameSite + CORS. |
| `POST /api/analysis/{jobId}/run` | `AnalysisController.runAnalysis` | Optional JSON body; defaults if body is absent | Bodyless/simple POST can match the handler shape. | Yes | Unallowed origin rejected | Browser CSRF risk is low due to auth + SameSite + CORS. If SameSite/CORS assumptions fail, this endpoint is state-changing and should get a CSRF token. |
| `POST /api/analysis/{jobId}/retry` | `AnalysisController.retryAnalysis` | Optional JSON body; defaults if body is absent | Bodyless/simple POST can match the handler shape. | Yes | Unallowed origin rejected | Same as `/run`. |
| `POST /api/analysis/{jobId}/cancel` | `AnalysisController.cancelAnalysis` | No body required | Form-submit compatible. | Yes | Origin-bearing simple POST from unallowed origin rejected | Browser CSRF risk is low under current assumptions, but this is form-shaped and state-changing. |
| `DELETE /api/results/{jobId}` | `ResultController.deleteResult` | No body | Native HTML form cannot send DELETE; JS fetch would preflight. | Yes | Unallowed origin rejected | Browser CSRF risk is low. |
| `POST /api/results/{jobId}/video-access-token` | `ResultController.issueVideoAccessToken` | No body | Form-submit compatible. | Yes | Unallowed origin rejected | Browser CSRF risk is low under SameSite/CORS. It mints a short-lived token, so keep it authenticated. |
| `DELETE /api/users/me` | `UserController.withdraw` | `@RequestBody WithdrawRequest`; intended JSON password body | Native HTML form cannot send DELETE and cannot send JSON body. JS fetch would preflight. | Yes | Unallowed origin rejected | Browser CSRF risk is low; password confirmation is an additional guard. |

No `PUT` or `PATCH` mappings were found under `backend/src/main/java/com/hanium/presentation/presentation/controller`.

## Verification on local profile

Environment:

- Backend: `SPRING_PROFILES_ACTIVE=local SERVER_PORT=18080 MANAGEMENT_SERVER_PORT=18081 ./gradlew bootRun`
- Cookie mode: `security.jwt.cookie-secure=false` from `application-local.yml`, so HTTP localhost can store the cookie.

Session flow:

1. `POST /api/auth/signup` with JSON returned `201`.
2. `POST /api/auth/login` with JSON returned `200` and `Set-Cookie: access_token=...; Path=/; Max-Age=1800; HttpOnly; SameSite=Lax`.
3. `GET /api/auth/me` using only the saved curl cookie jar returned `200` and the current user. This is the reload/session-restore simulation for the frontend's `/api/auth/me` initialization call.
4. `POST /api/auth/logout` using the saved cookie jar returned `200` and `Set-Cookie: access_token=; Path=/; Max-Age=0; ...; HttpOnly; SameSite=Lax`.
5. 2026-08-12 계약 변경 후 두 번째 `GET /api/auth/me`는 `200`과 `data:null`을 반환한다. 다른 보호 API의 익명 요청은 계속 `401`이다.

Cross-origin probes:

- `OPTIONS /api/analysis/{jobId}/run` with `Origin: http://evil.example`, `Access-Control-Request-Method: POST`, and `Access-Control-Request-Headers: content-type` returned `403 Invalid CORS request`.
- `POST /api/auth/logout` with `Origin: http://evil.example` and `application/x-www-form-urlencoded` returned `403 Invalid CORS request`.
- `POST /api/analysis/{jobId}/cancel` with `Origin: http://evil.example`, a valid logged-in cookie jar, and `application/x-www-form-urlencoded` returned `403 Invalid CORS request`.
- `POST /api/auth/login` with no `Origin` and `application/x-www-form-urlencoded` is covered by `AuthControllerIntegrationTest` and returns `415 Unsupported Media Type`, not `500`.
- `POST /api/auth/login` with malformed JSON is covered by `AuthControllerIntegrationTest` and returns `400 Bad Request`, not `500`.
- 인증 cookie가 있는 `POST /api/users/me/onboarding/skip`는 비허용 Origin, Origin 없는 브라우저 User-Agent, `Sec-Fetch-Site: same-site`에서 각각 `403`, 허용 Origin에서 `200`을 반환했다.

## Conclusion

현재 지원 범위의 브라우저 cookie 인증은 다음 다층 경계로 보호한다.

- State-changing GET endpoints were not found.
- JSON `fetch`/XHR from arbitrary origins is blocked by CORS preflight.
- Origin-bearing simple POSTs from arbitrary origins are Spring CORS와 cookie Origin 필터에서 거부된다.
- Authenticated state-changing endpoints require the JWT cookie, and `SameSite=Lax` prevents that cookie from being attached to cross-site POST/fetch requests.
- 같은 사이트의 다른 origin은 SameSite 쿠키가 첨부될 수 있으므로 Origin 정확 일치 또는 `Sec-Fetch-Site: same-origin` 검증으로 추가 차단한다.

남은 위험과 운영 제약은 다음과 같다.

1. `CORS_ALLOWED_ORIGINS`는 보안 설정이다. 임의 origin, wildcard 또는 소유하지 않은 origin을 추가하면 안 된다.
2. Fetch Metadata와 일반 브라우저 User-Agent를 모두 제거하는 특수·구형 브라우저는 비브라우저 클라이언트로 분류될 수 있다. 이런 클라이언트를 지원해야 하면 synchronizer/double-submit CSRF token으로 전환한다.
3. 향후 cross-site embedding이나 cross-origin 웹 클라이언트를 지원하면 현재 same-origin 정책과 충돌한다. 그때는 허용 origin 확대만 하지 말고 CSRF token과 클라이언트 계약을 함께 설계한다.
4. Wrong content type and malformed JSON for auth endpoints are covered by integration tests and return 4xx responses. Keep the regression tests when adding new JSON auth endpoints.

2026-08-12 검증에서는 허용·비허용 Origin, Origin 없는 브라우저, `same-site` Fetch Metadata, Origin 없는 비브라우저 요청을 통합 테스트로 고정했다. 전체 백엔드 테스트와 실제 Chromium 공개 랜딩도 통과했으며, 익명 `/api/auth/me`는 `200 + data:null`로 처리되어 이전의 예상된 401 콘솔 오류가 더 이상 발생하지 않았다.
