# Video LLM 실연동 활성화 결정 문서

작성일: 2026-07-16
상태: **결정 기록됨(2026-07-16) — "staging 우선, 운영 활성화 보류".** 코드는 완성돼 있으나 기본값은 비활성(`VIDEO_LLM_ENABLED=false`, `VIDEO_LLM_BACKEND=mock`) 유지입니다. 이 문서는 실제 운영에서 켜기 위해 확정해야 할 항목과, 코드로 미리 준비해 둔 안전장치를 정리합니다. **스위치를 켜는 것(=플래그 변경)은 비용·프라이버시 결정이 필요하므로 사용자 승인 없이 하지 않았습니다.**

## 결정 기록 (2026-07-16)

- **A-1 지금 켤지**: 운영은 **보류**, staging 우선. 아래 전제(A-3 고지, A-4 동의)가 갖춰진 뒤 staging에서만 켠다.
- **A-2 비용 한도**: 무료 한도 확인 전까지 `VIDEO_LLM_MONTHLY_RATE_LIMIT_CAPACITY=500` 유지, 초과 시 차단(mock 폴백).
- **A-3 국외이전 고지**: 아래 부록 초안을 **법률/지도 검토 1회 후** 개인정보처리방침에 삽입한다(삽입 전에는 실사용자 영상으로 켜지 않는다).
- **A-4 업로드 동의 UI**: 안내 문구 + 동의 체크를 추가한다. 단 실제 삽입/활성화는 Video LLM을 켜는 시점에 함께 반영(지금 켜지도 않았는데 "외부 전송 동의"를 받으면 사실과 어긋나므로 미리 넣지 않는다).
- **A-5 롤아웃**: 내부·베타부터.

즉 지금 당장의 코드 변경은 없고, 켜기로 결정하는 순간 A-3(고지 삽입) → A-4(동의 UI) → staging `VIDEO_LLM_ENABLED=true` 순으로 진행하면 된다.

## 무엇이 이미 준비됐나 (코드)

- NVIDIA NIM(`nvidia/nemotron-3-nano-omni-30b-a3b-reasoning`) 실호출 코드 완성: 에셋 업로드/202 폴링/JSON 정규화/타임아웃(`NVIDIA_VIDEO_LLM_TIMEOUT_SECONDS`=120s).
- 대용량 영상은 MinIO에서 임시 파일로 스트리밍하고 NVIDIA Asset API에도 1MB 청크로 전송합니다. `VIDEO_LLM_MAX_VIDEO_SIZE_MB`(기본 500MB)를 다운로드 헤더·실제 바이트·로컬 파일에 강제해 동시 분석의 메모리 급증과 과대 파일 전송을 막습니다.
- 실패 시 mock으로 자동 폴백(`generationMode=FALLBACK`).
- 월간 예산 가드(`VIDEO_LLM_MONTHLY_RATE_LIMIT_CAPACITY`=500, 보수적 기본값) + 서킷브레이커.
- **관측성(이번에 추가)**: `video_llm_generation_total{mode=REAL|FALLBACK|MOCK}` 메트릭과, 폴백률 50% 초과 시 발동하는 `VideoLlmFallbackRateHigh` Prometheus 알림.
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
NVIDIA_API_KEY=nvapi-...          # build.nvidia.com에서 발급
VIDEO_LLM_BACKEND=external-api     # 이미지 빌드 시(런타임 동작은 VIDEO_LLM_ENABLED가 결정)
```

1. staging에서 실영상 1~2건 업로드→분석 E2E 실행.
2. 결과의 `generationMode`가 `REAL`인지 확인(FALLBACK이면 키/네트워크 점검).
3. Grafana에서 `video_llm_generation_total{mode="REAL"}` 증가, `video_llm_monthly_usage` 증가, `NVIDIA_VIDEO_LLM_USAGE` 로그의 elapsedMs/비용 관찰.
4. 폴백률·예산 알림이 정상 동작하는지 확인.
5. 개인정보 고지·동의 반영 확인 후에만 운영 활성화.

## 롤백

- `VIDEO_LLM_ENABLED=false`로 되돌리면 즉시 mock으로 복귀(코드 변경/재배포 불필요, 환경변수만).

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
