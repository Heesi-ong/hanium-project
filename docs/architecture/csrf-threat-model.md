# CSRF threat model for cookie JWT auth

작성일: 2026-07-12

## Context

프론트엔드는 JWT를 `localStorage`에 저장하지 않고 `access_token` HttpOnly 쿠키로 인증한다. 쿠키 속성은 `SameSite=Lax`, `HttpOnly`, `Path=/`이고, local/dev HTTP 환경에서는 `Secure=false`, prod 기본값은 `Secure=true`이다. 별도 CSRF 토큰은 아직 구현하지 않았다.

이 문서는 현재 API가 SameSite 쿠키와 CORS만으로 브라우저 기반 CSRF에 충분히 방어되는지 확인하기 위한 위협모델이다.

## Current browser-side assumptions

- `SameSite=Lax` 쿠키는 교차 사이트 `fetch`/XHR 및 교차 사이트 POST form 제출에 자동 첨부되지 않는다. top-level safe navigation(GET 등)에는 첨부될 수 있지만, 현재 상태 변경 API는 GET으로 열려 있지 않다.
- Spring CORS 설정은 `/api/**`에 대해 `cors.allowed-origins`에 있는 origin만 허용하고 `allowCredentials(true)`를 사용한다.
- 기본 허용 origin은 `http://localhost:5173,http://127.0.0.1:5173`이다. 임의 origin의 preflight와 Origin-bearing simple POST는 로컬 검증에서 `403 Invalid CORS request`로 차단됐다.
- CORS는 원칙적으로 응답 읽기 제어이지 CSRF 토큰 대체물이 아니다. 다만 현재 서버 구성에서는 허용되지 않은 `Origin`이 붙은 simple POST도 Spring CORS 단계에서 컨트롤러 도달 전에 거절된다.

## State-changing endpoints

| Endpoint | Handler | Body / content type | HTML form triggerability | Auth required by `SecurityConfig` | Arbitrary-origin fetch/CORS result | CSRF assessment |
| --- | --- | --- | --- | --- | --- | --- |
| `POST /api/auth/signup` | `AuthController.signup` | `@RequestBody SignupRequest`; intended JSON | Plain form is not a valid JSON request. Origin-bearing form is rejected by CORS. | No (`permitAll`) | Preflight/simple request from unallowed origin rejected | Login-free account creation is not cookie-auth CSRF. Keep rate limiting. |
| `POST /api/auth/login` | `AuthController.login` | `@RequestBody AuthRequest`; intended JSON | Plain form is not valid JSON. No-Origin form-urlencoded is covered by integration tests and returns 415; Origin-bearing form is rejected by CORS. | No (`permitAll`) | Preflight/simple request from unallowed origin rejected | JSON login CSRF is blocked by preflight. Wrong content type is handled as a 4xx client error, not a 500. |
| `POST /api/auth/logout` | `AuthController.logout` | No body required | Shape is form-submit compatible. Origin-bearing form is rejected by CORS. No-Origin request returns expiring cookie. | No (`permitAll`) | Origin-bearing simple POST from unallowed origin rejected | Main residual concern is forced logout in clients that omit `Origin` on cross-site POST. Modern browser behavior plus current CORS reduces this, but logout is the weakest endpoint because it is anonymous and bodyless. |
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
5. A second `GET /api/auth/me` with the same cookie jar returned `401`.

Cross-origin probes:

- `OPTIONS /api/analysis/{jobId}/run` with `Origin: http://evil.example`, `Access-Control-Request-Method: POST`, and `Access-Control-Request-Headers: content-type` returned `403 Invalid CORS request`.
- `POST /api/auth/logout` with `Origin: http://evil.example` and `application/x-www-form-urlencoded` returned `403 Invalid CORS request`.
- `POST /api/analysis/{jobId}/cancel` with `Origin: http://evil.example`, a valid logged-in cookie jar, and `application/x-www-form-urlencoded` returned `403 Invalid CORS request`.
- `POST /api/auth/login` with no `Origin` and `application/x-www-form-urlencoded` is covered by `AuthControllerIntegrationTest` and returns `415 Unsupported Media Type`, not `500`.
- `POST /api/auth/login` with malformed JSON is covered by `AuthControllerIntegrationTest` and returns `400 Bad Request`, not `500`.

## Conclusion

For modern browsers, the current setup is adequate for this unit's stated scope:

- State-changing GET endpoints were not found.
- JSON `fetch`/XHR from arbitrary origins is blocked by CORS preflight.
- Origin-bearing simple POSTs from arbitrary origins are rejected by Spring CORS.
- Authenticated state-changing endpoints require the JWT cookie, and `SameSite=Lax` prevents that cookie from being attached to cross-site POST/fetch requests.

The residual risks are:

1. `POST /api/auth/logout` is anonymous and bodyless. If a client or legacy browser sends a cross-site POST without `Origin`, the endpoint can emit an expiring cookie and force logout.
2. Several authenticated endpoints are form-shaped (`cancel`, `run`, `retry`, `video-access-token`, `upload`). They are currently protected by SameSite/CORS, but they are exactly the endpoints that should receive a CSRF token if SameSite policy, allowed origins, or embedded-client assumptions change.
3. Wrong content type and malformed JSON for auth endpoints are now covered by integration tests and return 4xx responses. Keep the regression tests when adding new JSON auth endpoints.

Recommended next step before production hardening: keep this unit as analysis-only, then add a dedicated CSRF-token design if the app must support broader allowed origins, cross-site embedding, older browser support, or non-browser clients that do not reliably send `Origin`.
