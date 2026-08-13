# 테스트 기능 보강 적용 보고서

- 적용일: 2026-08-13
- 범위: 로컬·통제된 학생 프로젝트 테스트
- 배포 변경: 없음

## 적용 결과

| 항목 | 적용 내용 | 얻는 결과물 |
|---|---|---|
| coverage gate | frontend와 두 Python 엔진에 70% 최소 기준 적용 | `frontend/coverage/`, 각 엔진 `htmlcov/`·`coverage.xml` |
| API 계약 | 두 Python 엔진의 요청·응답을 Pydantic 모델로 고정하고 OpenAPI 테스트 추가 | 각 엔진 `openapi.json` CI artifact, 잘못된 응답의 조기 탐지 |
| backend 계약 | MockMvc 계약 테스트에서 OpenAPI JSON 저장 | `backend/build/contracts/backend-openapi.json` |
| 실제 인프라 | Testcontainers MySQL 8.4 Flyway, Redis 7 인증·TTL 검증 | H2만으로 찾지 못하는 migration·Redis 설정 회귀 탐지 |
| golden 영상 | `sample-demo.mp4`의 총점 48 ±3 회귀 기준 추가 | CI 로그의 expected/actual/drift와 실패 증거 |
| Video LLM 경계 | 안전한 `jobId`, FPS·프레임 수·영상 길이 범위 검증 | 잘못된 내부 요청의 422 조기 거부 |

## 검증 기준

- golden 값은 분석 공식이나 기준 영상이 의도적으로 변경된 경우에만 리뷰 후 버전을 올린다.
- 부분 테스트에서 coverage가 낮다는 이유로 실패하는 것을 피하려면 진단 실행에만 `--no-cov`를
  사용하고, 최종 판정은 항상 전체 `pytest`로 한다.
- Testcontainers는 Docker가 없으면 skip된다. 실행 증거가 필요한 검토에서는 XML의 `skipped`
  개수를 확인하며, Docker Engine 29는 기본 API 1.44를 사용한다.
- OpenAPI artifact는 현재 계약의 스냅샷이다. 아직 frontend 코드 생성이나 이전 스냅샷과의
  breaking-change 자동 diff까지 수행하지는 않는다.

## 이번 실측

- frontend: 51 files, 288 tests; statements 78.33%, branches 71.41%, functions 80.55%, lines 79.04%
- analysis-engine: 170 tests; total coverage 87.42%
- video-llm-engine: 209 tests passed, live 1 test deselected; total coverage 93.89%
- backend: 전체 547 tests 실패 0; 별도 실제 Testcontainers 2 tests passed
- golden E2E: expected 48, actual 48, drift 0, tolerance 3

## 남은 선택 사항

현재 로컬 완료 흐름을 막는 필수 항목은 없다. 팀 규모가 커질 때 다음을 별도 승인해 확장할 수 있다.

1. OpenAPI 이전 스냅샷과의 breaking-change 자동 diff
2. OpenAPI에서 frontend 타입 자동 생성
3. 여러 영상·조명·해상도를 포함한 golden fixture 묶음
4. 실제 NVIDIA/OpenAI 키를 사용하는 수동 acceptance와 비용 상한
