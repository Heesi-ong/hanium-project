# 테스트 고도화 ③~⑥ 적용 보고서

- 적용일: 2026-08-14
- 범위: 로컬 PC·통제된 CI에서의 학생 프로젝트 시연
- 배포 및 실제 외부 AI 호출: 없음

## 적용 항목

| 번호 | 변경 | 사용 방법 | 얻는 결과물 |
|---|---|---|---|
| ③ | 두 종류의 파생 영상과 golden matrix E2E | `scripts/prepare-golden-video-fixtures.sh` 후 `E2E_GOLDEN_MATRIX=true`로 Playwright 실행 | 영상별 expected/actual/drift 로그, 점수·설명 계약 회귀 판정 |
| ④ | CI의 Testcontainers 실제 실행 강제 | backend test XML에서 tests=2, skipped=0, failures=0, errors=0 검사 | Docker 미가동으로 테스트가 skip된 CI의 거짓 통과 방지 |
| ⑤ | 실제 장애·복구 E2E | 전용 Compose 스택에서 `E2E_FAILURE_RECOVERY=true`로 실행 | 손상 파일 4xx, 엔진 장애 FAILED/failReason, retry COMPLETED 증거 |
| ⑥ | 점수 설명 메타데이터 | 분석 결과의 `scoreExplanation` 조회 | 산식 버전, 가중치, 항목별 기여도, 반올림·감점·clamp 근거 |

## 점수 설명 계약

정량 총점 계산은 기존과 동일하게 유지했다. `weighted-v1` 설명에는 다음 필드를 추가했다.

- `weights`, `weightedContributions`, `weightedScoreBeforeRounding`
- `roundingPolicy=truncate_toward_zero`, `rawScore`
- `penaltyApplied`, `penaltyReasons`, `clampRange`

분석 실패 결과에는 설명을 꾸미지 않고 `available=false`, `reason=analysis_failed`를 반환한다.
따라서 mock·fallback·실패 결과를 실제 정량 분석 설명으로 오인하지 않는다.

## Golden 기준

| fixture | 입력 특성 | 기대 총점 | 허용 편차 |
|---|---|---:|---:|
| `sample-short-6s.mp4` | 기준 영상의 앞 6초, 음성 유지 | 43 | ±3 |
| `sample-dark-muted-12s.mp4` | 12초, 320px, 저조도·저채도, 무음 | 14 | ±3 |

파생 MP4는 git에 저장하지 않는다. 생성 스크립트와 JSON 기준만 버전 관리하므로 원본
`sample-demo.mp4`가 같으면 로컬과 CI에서 동일하게 재생성할 수 있다. 기대값이나 허용 편차는
분석 로직 또는 fixture를 의도적으로 변경하고 결과를 검토한 경우에만 갱신한다.

## 장애·복구 판정

1. 손상된 MP4 업로드가 4xx로 거부되는지 확인한다.
2. 정상 영상을 올린 뒤 `analysis-engine`을 중단한다.
3. 작업이 `FAILED`가 되고 사용자 조회 응답에 `failReason`이 있는지 확인한다.
4. 같은 컨테이너를 시작해 최초 환경변수를 보존하고 health를 기다린다.
5. backend Circuit Breaker 기본 open 기간 30초보다 긴 안정화 시간을 지난다.
6. retry 후 `COMPLETED`, 결과 총점, `scoreExplanation.formulaVersion`을 확인한다.

## 로컬 실측

- 다중 golden: 2 passed — short 43/43, dark-muted 14/14
- 장애·복구: 1 passed — 실제 엔진 중단과 재시도 완료, 약 1.3분
- 기존 단일 golden: 1 passed — expected 48, actual 50, drift 2, tolerance 3
- analysis-engine 점수 집중 테스트: 69 passed
- backend 결과 병합 집중 테스트: passed

전체 단위 테스트와 Testcontainers 최종 결과는 이 변경의 최종 검증 보고에서 별도로 기록한다.

## 남은 위험

- MediaPipe·FFmpeg 실행 환경 차이로 총점에 작은 변동이 있어 golden은 고정값이 아니라 검토된
  허용 편차로 판정한다.
- 장애 E2E는 실제 컨테이너를 중단하므로 개발자가 사용하는 공용 Compose 프로젝트에서 실행하면
  작업을 방해할 수 있다. CI나 별도 `COMPOSE_PROJECT_NAME` 스택에서만 실행한다.
- 점수 설명은 현재 계산 근거의 투명성을 높이지만, 점수의 교육적 타당성 자체를 보증하지 않는다.
- 외부 NVIDIA/OpenAI 실제 호출, 비용, 공급자 장애는 이 로컬 무비용 검증 범위에 포함하지 않는다.
