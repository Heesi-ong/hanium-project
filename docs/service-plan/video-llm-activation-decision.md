# Video LLM 실연동 활성화 결정 문서

작성일: 2026-07-16

**2026-07-23 실패 정책 갱신**: 런타임 정책을 `VIDEO_LLM_POLICY=STRICT|DEGRADED|DISABLED`로
명시했다. STRICT는 실제 모델 실패 시 엔진이 502를 반환해 backend 작업도 실패시키며,
DEGRADED만 `generationMode=FALLBACK` 샘플 대체를 허용한다. DISABLED는 의도된 MOCK
모드다. 기존 `VIDEO_LLM_ENABLED`는 정책 값이 비었을 때만 사용하는 하위 호환 스위치다.
운영 정확성을 우선하는 기본 권고는 STRICT이며, DEGRADED는 샘플 대체를 제품이 명시적으로
허용한 환경에서만 선택한다.

**2026-07-27 긴 영상 부분 성공 정책 갱신**: 구간 분할된 긴 영상은 모든 세그먼트의
생성·NVIDIA 분석이 성공해야만 `generationMode=REAL`을 반환한다. 이전에는 한 구간만
성공해도 누락 구간이 있는 결과를 REAL로 표시할 수 있어 STRICT와 `requireReal` 계약을
위반했다. 이제 일부 실패도 전체 실제 호출 실패로 승격되어 STRICT/재분석은 502,
DEGRADED 일반 분석만 전체 FALLBACK으로 처리된다. 비-live 엔진 테스트 177건으로
정책·분할·정규화 회귀를 확인했다.

**2026-07-20 활성화 및 A-4 결정 갱신**: 사용자가 실제 NVIDIA API 키(`nvapi-...`)를
제공해 로컬 `.env`(git-ignored)에 설정하고 `VIDEO_LLM_ENABLED=true`로 전환했다.
`readiness` 엔드포인트(`mode: REAL, realModelReady: true`)와 실제 업로드→분석
완료까지의 E2E로 실호출 성공(`generationMode=REAL`, NVIDIA 응답 200)을 확인했다.
이로써 아래 A-1 "보류" 상태는 **이 환경에서는 종료**되었다.

이 전환으로 A-4(업로드 동의 UI)의 전제("켜지도 않았는데 동의를 미리 받으면 사실과
어긋난다")가 깨졌으므로 사용자에게 처리 방침을 물었다. **결정: PrivacyPage의 기존
국외이전 고지(A-3, 아래 참고)만으로 충분하다고 판단 — 업로드 시점 별도 동의
체크박스는 추가하지 않는다.** 즉 A-4는 "미구현"이 아니라 "구현하지 않기로 결정,
PrivacyPage 고지로 대체"로 판정을 변경한다. 향후 이 결정을 뒤집으려면(예: 별도
동의 UI가 실제로 필요해지는 법적/정책 변화가 있으면) 이 절을 갱신할 것.

**2026-07-20 이전 상태 갱신** (아래는 활성화 전 시점 기록, 참고용으로 남김): 기본값은
여전히 비활성(`VIDEO_LLM_ENABLED=false`)이며 이 문서의 "보류" 결정 자체는 유지된다.
다만 아래 항목 상태가 실제와 어긋나 있어 갱신한다.
- **A-3(국외이전 고지) 문구는 이미 `frontend/src/pages/PrivacyPage.jsx`에 반영되어
  있음을 확인했다**(이전받는 자/이전 항목/이전 국가/이전 목적/이전 방법/보유 기간까지
  OpenAI·NVIDIA 각각 서술됨). 다만 "법률/지도 검토 1회"를 실제로 거쳤는지는 코드에서
  확인할 수 없는 사실이라 별도로 확인이 필요하다.
- **A-4(업로드 동의 UI)는 여전히 미구현이다.** 이는 오류가 아니라 이 문서 자체의 의도된
  설계다 — "지금 켜지도 않았는데 외부 전송 동의를 받으면 사실과 어긋나므로 미리 넣지
  않는다"는 원칙에 따라, `VIDEO_LLM_ENABLED=true`로 실제 전환하는 시점에 함께 넣기로
  되어 있다. `UploadPage.jsx`의 "Video LLM 분석 사용" 체크박스에는 현재 NVIDIA 전송에
  대한 언급이 없다(mock 모드에서는 사실이 아니므로 의도적으로 비워둠).
- 이번에 코드로 추가된 안전장치(사용자별 일일 한도, 실제 모델 호출 동시 실행 수 제한,
  긴 영상 구간 분할, 구간 길이별 프롬프트 분기)는 아래 A-2(비용 한도)와 인접한 주제라
  `docs/service-plan/video-llm-model-options.md` 10~11절에 정리해 뒀다.

상태: **결정 기록됨(2026-07-16) — "staging 우선, 운영 활성화 보류".** 코드는 완성돼 있으나 기본값은 비활성(`VIDEO_LLM_ENABLED=false`, `VIDEO_LLM_BACKEND=mock`) 유지입니다. 이 문서는 실제 운영에서 켜기 위해 확정해야 할 항목과, 코드로 미리 준비해 둔 안전장치를 정리합니다. **스위치를 켜는 것(=플래그 변경)은 비용·프라이버시 결정이 필요하므로 사용자 승인 없이 하지 않았습니다.**

## 결정 기록 (2026-07-16)

- **A-1 지금 켤지**: 운영은 **보류**, staging 우선. 아래 전제(A-3 고지, A-4 동의)가 갖춰진 뒤 staging에서만 켠다.
- **A-2 비용 한도**: 무료 한도 확인 전까지 `VIDEO_LLM_MONTHLY_RATE_LIMIT_CAPACITY=500` 유지, 초과 시 차단(mock 폴백). (2026-07-20 추가: 이와 별개로 사용자별 일일 한도 `VIDEO_LLM_DAILY_RATE_LIMIT_CAPACITY=10`도 추가됨.)
- **A-3 국외이전 고지**: 아래 부록 초안을 **법률/지도 검토 1회 후** 개인정보처리방침에 삽입한다(삽입 전에는 실사용자 영상으로 켜지 않는다). (2026-07-20: 문구 자체는 이미 삽입되어 있음 — 위 상태 갱신 참고.)
- **A-4 업로드 동의 UI**: 안내 문구 + 동의 체크를 추가한다. 단 실제 삽입/활성화는 Video LLM을 켜는 시점에 함께 반영(지금 켜지도 않았는데 "외부 전송 동의"를 받으면 사실과 어긋나므로 미리 넣지 않는다). (2026-07-20: 여전히 미구현, 의도된 상태.)
- **A-5 롤아웃**: 내부·베타부터.

위 항목은 2026-07-16 당시 계획이다. 현재 절차는 상단의 2026-07-20 결정과
2026-07-23 정책 갱신을 우선하며, staging에서 `VIDEO_LLM_POLICY=STRICT|DEGRADED`를
명시한 뒤 검증한다.

## 무엇이 이미 준비됐나 (코드)

- NVIDIA NIM(`nvidia/nemotron-3-nano-omni-30b-a3b-reasoning`) 실호출 코드 완성: 에셋 업로드/202 폴링/JSON 정규화/타임아웃(`NVIDIA_VIDEO_LLM_TIMEOUT_SECONDS`=120s).
- 대용량 영상은 MinIO에서 임시 파일로 스트리밍하고 NVIDIA Asset API에도 1MB 청크로 전송합니다. `VIDEO_LLM_MAX_VIDEO_SIZE_MB`(기본 500MB)를 다운로드 헤더·실제 바이트·로컬 파일에 강제해 동시 분석의 메모리 급증과 과대 파일 전송을 막습니다.
- STRICT는 외부 제공자 호출 실패를 502로 반환하고, DEGRADED만 mock으로 자동
  폴백(`generationMode=FALLBACK`)한다.
- 긴 영상은 모든 세그먼트 생성·실제 호출이 성공한 경우에만 REAL이다. 빈 세그먼트나
  부분 네트워크 실패가 있으면 불완전한 결과를 REAL로 표시하지 않는다.
- 정책이 STRICT/DEGRADED인데 키·URL·timeout·크기 제한이 잘못되면 시작 단계에서 실패해
  설정 오류를 숨기지 않는다.
- 영상 길이와 공통 청크 길이로 예상 세그먼트 호출 수를 계산해 원자적으로 예약하는
  월간 예산 가드(`VIDEO_LLM_MONTHLY_RATE_LIMIT_CAPACITY`=500, 보수적 기본값) + 서킷브레이커.
- **관측성(이번에 추가)**: `video_llm_generation_total{mode=REAL|FALLBACK|MOCK}` 메트릭과,
  실제 시도(REAL+FALLBACK) 중 폴백률 50% 초과 시 발동하는
  `VideoLlmFallbackRateHigh` Prometheus 알림. DISABLED의 의도된 MOCK은 분모에서 제외한다.
- **사용자 오해 방지(이번에 추가)**: 결과 화면(`FeedbackSection.jsx`)에서 `generationMode`가 MOCK/FALLBACK이면 "이 결과는 실제 영상 분석이 아닌 예시(샘플) 데이터"라는 경고 문구를 노출.

## 켜기 전 확정해야 할 것 (결정 항목)

1. **비용/한도**: NVIDIA API Catalog 무료 한도의 정확한 상한을 확인하고, `VIDEO_LLM_MONTHLY_RATE_LIMIT_CAPACITY`를 실제 한도에 맞춰 조정. 유료 전환 가능성과 상한 초과 시 정책(차단 유지) 확인.
2. **개인정보 국외 이전 고지**: 영상이 NVIDIA(미국) 서버로 전송되므로, 개인정보처리방침에 "개인정보 국외 이전 / 처리위탁" 고지 필요(아래 초안). **이 고지 없이 실사용자 영상으로 켜면 안 됩니다.**
3. **동의 흐름**: 업로드 시점에 "분석을 위해 영상이 외부 AI 모델(NVIDIA)로 전송됨"을 사용자가 인지·동의하도록 문구/체크 배치 여부.
4. **보관/삭제**: NVIDIA 측에 업로드된 에셋이 분석 후 삭제되는지 확인(코드상 `delete_nvidia_asset`로 정리 시도함). 실패 시 로그(`NVIDIA_VIDEO_LLM_ASSET_CLEANUP_FAILED`) 모니터링.
5. **롤아웃 범위**: 전체 사용자 vs 내부/베타 사용자 제한 시작 여부.

## 활성화 절차 (결정 후, staging부터)

```bash
# staging에서만. video-llm-engine에 실제 키와 함께:
VIDEO_LLM_ENABLED=true
VIDEO_LLM_POLICY=STRICT            # 샘플 대체를 허용할 때만 DEGRADED
NVIDIA_API_KEY=nvapi-...          # build.nvidia.com에서 발급
VIDEO_LLM_BACKEND=external-api     # 이미지 build arg와 런타임 식별값을 일치시킴
```

1. staging에서 실영상 1~2건 업로드→분석 E2E 실행.
2. 결과의 `generationMode`가 `REAL`인지 확인(FALLBACK이면 키/네트워크 점검).
3. Grafana에서 `video_llm_generation_total{mode="REAL"}` 증가, `video_llm_monthly_usage` 증가,
   세그먼트별 `NVIDIA_VIDEO_LLM_USAGE` 로그의 elapsedMs/status를 관찰한다. 월간 카운터는
   예상 세그먼트 수를 선예약하며, 공급자 응답·정리 실패 등 실제 시도 결과는 로그와 함께 본다.
4. 폴백률·예산 알림이 정상 동작하는지 확인.
5. 개인정보 고지·동의 반영 확인 후에만 운영 활성화.

## 롤백

- `VIDEO_LLM_POLICY=DISABLED`와 `VIDEO_LLM_ENABLED=false`로 되돌리면 즉시 MOCK 모드로
  복귀한다(코드 변경 없이 환경변수와 서비스 재기동만 필요).

---

## 부록 — 개인정보처리방침 추가 조항 초안 (붙여넣기용)

> 아래는 초안입니다. 실제 사업자 정보·법률 검토를 거쳐 확정하세요. 삽입 위치: 개인정보처리방침의 "개인정보의 제3자 제공 / 처리위탁 / 국외 이전" 항목(현재 `frontend/src/pages/PrivacyPage.jsx` 및 관련 문서).

### 개인정보의 국외 이전 및 처리위탁 (AI 영상 분석)

당사는 발표 영상에 대한 시각 분석(시선·표정·제스처·자세 등) 기능을 제공하기 위해, 이용자가 업로드한 영상 파일을 아래와 같이 국외의 인공지능 모델 제공사에 전송·위탁 처리합니다.

- 이전받는 자: NVIDIA Corporation (미국)
- 이전되는 항목: 이용자가 업로드한 발표 영상 파일 및 분석에 필요한 최소한의 메타데이터(영상 길이 등)
- 이전 국가: 미국
- 이전 목적: 영상 기반 발표 시각 분석 결과 생성
- 이전 방법: 분석 요청 시 암호화된 통신(HTTPS)을 통한 전송
- 보유·이용 기간: 분석 완료 후 지체 없이 삭제(당사는 분석 종료 시 업로드된 에셋 삭제를 요청합니다). 제공사의 데이터 처리 정책은 제공사 약관을 따릅니다.

이용자는 위 국외 이전에 동의하지 않을 수 있으며, 이 경우 영상 시각 분석 기능의 이용이 제한될 수 있습니다. (음성·정량 분석 등 국외 이전이 필요 없는 기능은 계속 이용 가능합니다.)

> 참고: 위 "분석 완료 후 삭제"는 현재 코드가 분석 직후 NVIDIA 에셋 삭제를 시도하는 동작(`delete_nvidia_asset`)에 근거합니다. 삭제 실패 로그가 반복되면 고지 내용과 실제 동작이 어긋나므로 반드시 모니터링하세요.
