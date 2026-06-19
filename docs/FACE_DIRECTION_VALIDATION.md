# 얼굴 방향 실험 지표 검증

## 현재 상태

- 기존 `gaze_score`는 현재 종합 점수에 사용되는 결정적 점수다.
- MediaPipe 변환 행렬 기반 `head_direction_score`, `yaw_degrees`,
  `pitch_degrees`, `roll_degrees`는 실험 지표다.
- 실험 지표는 기존 점수와 함께 저장되지만 종합 점수에는 포함되지 않는다.
- 정답 라벨이 없는 결과끼리의 일치율은 정확도가 아니라 두 계산 방식의 차이를
  확인하는 참고 자료다.

## 기존 결과 비교

프로젝트 루트에서 분석 결과 JSON을 지정하면 기존 점수와 실험 지표를 비교할 수 있다.
원본 영상 경로나 파일명은 출력하지 않는다.

```bash
.venv/bin/python scripts/export-face-direction-validation.py \
  Back/storage/results/RESULT_ID.json
```

프레임별 비교 데이터를 CSV로 내보낼 수 있다.

```bash
.venv/bin/python scripts/export-face-direction-validation.py \
  Back/storage/results/RESULT_ID.json \
  --format csv \
  --output /tmp/face-direction-validation.csv
```

JSON 요약에는 다음 항목이 포함된다.

- 전체 타임라인 프레임 수
- 얼굴 감지 프레임 수
- 기존 점수와 실험 점수의 유효 프레임 수
- 두 점수를 함께 비교할 수 있는 프레임 수
- 두 점수의 정확 일치율
- 두 점수의 평균 절대 차이

## 확정된 정답 기반 검증 기준

실제 정확도와 각도 오차는 사람이 작성한 정답 라벨을 기준으로 계산한다.

- yaw·pitch·roll 실제 각도와 정면·좌·우·상·하 방향 범주를 모두 기록한다.
- 기본 라벨링 간격은 1초다.
- 일반 촬영 조건의 얼굴 감지율은 95% 이상이어야 한다.
- yaw·pitch 평균 절대 오차는 10도 이하여야 한다.
- roll 평균 절대 오차는 8도 이하여야 한다.
- 방향 분류 정확도는 90% 이상이어야 한다.
- 기준 충족 전까지 실험 지표를 기존 종합 점수에 포함하지 않는다.

포함할 촬영 조건과 참여 인원·영상 수는 아직 미확정이다. 이 항목을 확정하기 전에는
정식 검증 데이터 수집을 시작하지 않는다.
