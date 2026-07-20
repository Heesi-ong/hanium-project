# Video LLM 실제 모델 도입 옵션 비교

작성일: 2026-07-03

## 1. 현재 전제

현재 `video-llm-engine`은 실제 영상을 분석하지 않고, 입력 영상과 무관하게 고정된 mock 응답을 반환한다.

- 현재 mock 위치: `video-llm-engine/app/api/video_llm_analysis.py:25-79`
- backend가 기대하는 응답 스키마: `backend/src/main/java/com/hanium/presentation/infrastructure/client/videollm/dto/VideoLlmEngineResponse.java:6-12`
  - `jobId`
  - `status`
  - `model`
  - `observations.eyeContact[]`
  - `observations.facialExpression[]`
  - `observations.gesture[]`
  - `observations.posture[]`
  - `globalSummary`

즉 어떤 모델을 선택하든 `video-llm-engine`의 외부 계약은 유지하고, 내부 구현만 실제 모델 호출 또는 로컬 추론으로 교체하는 것이 안전하다.

또한 `analysis-engine`에는 이미 프레임 샘플링에 가까운 로직이 있다.

- `MAX_EXTRACTED_FRAMES = 20`: `analysis-engine/app/api/basic_analysis.py:26`
- 프레임 추출: `analysis-engine/app/api/basic_analysis.py:355`
- 프레임 인덱스 상한 계산: `analysis-engine/app/api/basic_analysis.py:416-431`

따라서 프레임 기반 vision 모델은 기존 구조와 잘 맞고, 네이티브 영상 입력 모델은 별도 업로드/파일 API 통합이 필요하다.

## 2. 옵션 A: OpenAI GPT-4o vision, 프레임 이미지 기반

### 개요

영상 전체를 그대로 보내는 대신, 일정 간격으로 샘플링한 프레임 이미지를 GPT-4o vision 계열에 전달하고, 모델이 각 프레임과 타임스탬프를 기반으로 시선/표정/제스처/자세 관찰값을 생성하게 하는 방식이다.

### 가격

- GPT-4o 입력: 1M 토큰당 **$2.50**
- GPT-4o 출력: 1M 토큰당 **$10.00**
- Batch API: 입력 **$1.25**, 출력 **$5.00** / 1M 토큰
- 단, Batch API는 최대 24시간 처리 지연을 전제로 하므로 업로드 후 즉시 결과를 기대하는 현재 분석 흐름에는 부적합하다.

출처:

- [PE Collective - GPT-4o Pricing](https://pecollective.com/tools/gpt-4o-pricing/)
- [PricePerToken - GPT-4o API Pricing](https://pricepertoken.com/pricing-page/model/openai-gpt-4o)

참고로 위 조사 자료 기준 GPT-4.1은 텍스트 용도에서는 더 저렴하거나 권장될 수 있지만, 이미지 입력을 지원하지 않는다고 설명되어 있다. 따라서 프레임 이미지 기반 vision 분석에는 GPT-4o 계열을 우선 검토해야 한다.

### 장점

- 기존 OpenAI 운영 인프라를 재사용할 수 있다.
  - `OPENAI_API_KEY`, `OPENAI_MODEL`, timeout, 토큰 사용량 로그, `REAL/MOCK/FALLBACK` 패턴, 재시도 시 `REAL` 응답 재사용 정책이 이미 backend에 존재한다.
- 새 벤더 계약이나 별도 과금 계정 없이 시작할 수 있다.
- 프레임 개수에 상한을 두면 비용 상한이 비교적 명확하다.
  - 예: `MAX_EXTRACTED_FRAMES`처럼 최대 20장만 보내면, 영상 길이가 3분이든 30분이든 vision 입력 비용은 프레임 수 기준으로 제한된다.
- backend가 이미 OpenAI 결과에 대해 사용량 로그와 재사용 정책을 갖고 있어, 같은 패턴을 `video-llm-engine`에도 옮기기 쉽다.

### 단점

- 네이티브 영상 이해가 아니라 프레임 기반 분석이다.
- 프레임 사이에 발생하는 짧은 제스처, 시선 이동, 자세 변화는 놓칠 수 있다.
- 영상의 시간적 흐름을 모델이 직접 보는 것이 아니라, "타임스탬프가 붙은 이미지 묶음"을 보고 추론하는 방식이다.
- 프레임 인코딩/전송 방식과 프롬프트 설계에 따라 입력 토큰이 크게 달라질 수 있다.

### 적합한 경우

- 빠르게 mock을 제거하고 실제 모델 기반 응답을 붙이는 것이 우선인 경우
- 비용 예측 가능성이 중요한 경우
- 기존 OpenAI 키/운영 체계를 최대한 재사용하고 싶은 경우
- 발표 영상의 전체 흐름보다, 샘플 프레임 기준의 시각적 관찰만으로도 1차 서비스 품질을 낼 수 있다고 판단하는 경우

## 3. 옵션 B: Google Gemini, 네이티브 영상 입력

### 개요

영상 파일 또는 영상 데이터를 Gemini API에 직접 전달하고, 모델이 시간 흐름을 포함해 분석하게 하는 방식이다. 프레임 샘플링 손실이 적고, "영상 전체를 본 모델"이라는 제품 설명을 하기 쉽다.

### 가격

Gemini는 모델별로 가격 폭이 크다. 공식 가격표 기준 일부 모델은 text/image/video 입력에 대해 1M 토큰당 **$0.05~$0.30** 수준의 저가 Flash 계열부터, Pro/상위 모델에서는 **$1~$4+** 수준까지 올라간다. 외부 가격 가이드는 2026년 기준 Gemini API 입력 비용 범위를 대략 **$0.10~$4.00 / 1M tokens**로 정리한다.

영상 관련 과금은 영상 길이에 비례하는 성격이 강하다. Google 공식 가격표에는 720p video에 대해 초당 **5,792 tokens**로 계산되는 항목이 있으며, 일부 Standard 가격에서는 초당 약 **$0.10** 수준으로 설명된다.

출처:

- [Google AI for Developers - Gemini API pricing](https://ai.google.dev/gemini-api/docs/pricing)
- [LaoZhang AI Blog - Gemini API Pricing Guide](https://blog.laozhang.ai/en/posts/gemini-api-pricing)

### 장점

- 영상 파일을 직접 분석할 수 있어 프레임 샘플링 손실이 적다.
- 시선 이동, 제스처 전환, 자세 변화처럼 시간 흐름이 중요한 항목에 더 유리할 수 있다.
- "진짜 영상 이해"에 가까운 구현이 가능하다.
- 모델에 따라 긴 컨텍스트와 멀티모달 처리 기능을 활용할 수 있다.

### 단점

- 새 벤더 계약, API 키, SDK 또는 HTTP 클라이언트 통합이 필요하다.
- 현재 backend의 OpenAI 사용량 로깅/재사용/폴백 구조를 그대로 쓸 수 없고, Gemini용으로 별도 구현해야 한다.
- 영상 길이에 따라 토큰과 비용이 계속 늘어날 수 있어, 프레임 상한 방식보다 비용 예측이 어렵다.
- 긴 발표 영상에서는 파일 업로드 시간, 처리 지연, API timeout, 재시도 비용 관리가 새 리스크가 된다.

### 적합한 경우

- 샘플 프레임이 아니라 영상 흐름 자체를 이해하는 품질이 중요할 때
- 비용보다 분석 품질과 제품 메시지가 더 중요할 때
- Google Cloud/Gemini 계정과 운영 경험이 있거나 도입할 계획이 있을 때
- 영상 길이 제한, 사용자별 월간 사용량 제한, 예산 알림 같은 비용 통제 장치를 함께 도입할 수 있을 때

## 4. 옵션 C: 오픈소스 로컬 모델, 예: Qwen2.5-VL / Qwen 계열 VL

### 개요

오픈소스 vision-language 모델을 자체 GPU 서버에서 서빙하고, `video-llm-engine`이 로컬 모델 서버를 호출하는 방식이다. API 사용료는 없지만, GPU 서버와 모델 운영 비용을 직접 부담한다.

사용자 요청에서 예시로 든 Qwen2.5-VL 계열뿐 아니라, 2026년 기준 Qwen VL 계열은 이미지/영상 이해, OCR, 긴 컨텍스트 영상 이해를 강점으로 소개되는 사례가 있다.

출처:

- [BentoML - Multimodal AI: Open-Source Vision Language Models](https://www.bentoml.com/blog/multimodal-ai-a-guide-to-open-source-vision-language-models)

### 장점

- 외부 API 호출 비용이 없다.
- 영상/프레임 데이터가 외부 벤더로 나가지 않는다.
- 모델을 직접 고정할 수 있어, 벤더 API 변경이나 가격 변경의 영향을 덜 받는다.
- 장기적으로 사용량이 매우 많아지면 API 과금보다 유리할 수 있다.

### 단점

- 자체 GPU 서버가 필요하다.
- 현재 프로젝트의 로컬/단일 인스턴스 운영 구조에서 GPU 사용 가능 여부가 검증되지 않았다.
- 모델 서빙, GPU 메모리, cold start, 동시 요청 처리, 큐잉, 장애 복구가 모두 새 운영 부담이다.
- 모델 품질을 직접 평가해야 하고, 응답을 현재 backend 스키마로 안정적으로 정규화하는 후처리도 필요하다.
- 개발 난이도와 운영 난이도가 세 옵션 중 가장 높다.

### 적합한 경우

- 외부 API 비용 또는 데이터 외부 전송이 절대적으로 부담되는 경우
- GPU 서버를 확보할 수 있고 모델 서빙 운영 역량이 있는 경우
- 장기적으로 대량 분석 요청을 처리할 계획이 있는 경우
- 초기 구현 속도보다 독립성과 비용 구조 통제가 중요한 경우

## 5. 옵션 D: NVIDIA build.nvidia.com Nemotron omni-modal reasoning 무료 엔드포인트

### 개요

NVIDIA API Catalog(build.nvidia.com)의 `nvidia/nemotron-3-nano-omni-30b-a3b-reasoning` hosted API를 호출하는 방식이다. 공식 API 문서에는 이 모델이 `POST https://integrate.api.nvidia.com/v1/chat/completions`를 사용한다고 명시되어 있고, content 배열에서 영상은 `{"type":"video_url","video_url":{"url":"..."}}` 형식으로 전달한다. 문서상 MP4, MOV, WEBM 영상 입력을 지원하며, video/audio 요청은 system prompt에 `/no_think`를 사용해야 한다.

이번 구현에서는 `video-llm-engine` 내부의 `call_real_video_llm_model()`만 NVIDIA 호출로 교체하고, backend가 기대하는 `jobId/status/model/observations/globalSummary` 계약은 그대로 유지한다. `VIDEO_LLM_ENABLED=false` 기본값도 유지하므로, 실제 `nvapi-` 키를 넣고 검증하기 전까지 기존 mock 경로가 기본 동작이다.

출처:

- [NVIDIA API Reference - nvidia/nemotron-3-nano-omni-30b-a3b-reasoning infer](https://docs.api.nvidia.com/nim/reference/nvidia-nemotron-3-nano-omni-30b-a3b-reasoning-infer)
- [NVIDIA API Reference - nvidia/nemotron-3-nano-omni-30b-a3b-reasoning status polling](https://docs.api.nvidia.com/nim/reference/nvidia-nemotron-3-nano-omni-30b-a3b-reasoning-statuspolling)
- [NVIDIA NIM for VLMs - Overview](https://docs.nvidia.com/nim/vision-language-models/latest/introduction.html)
- [NVIDIA Cloud Functions API Reference - Create Asset](https://docs.api.nvidia.com/cloud-functions/reference/createasset)
- [USAGov - Learn about copyright and federal government materials](https://www.usa.gov/government-copyright)

### 확인한 사실

- hosted API 모델 ID: `nvidia/nemotron-3-nano-omni-30b-a3b-reasoning`
- API endpoint: `POST https://integrate.api.nvidia.com/v1/chat/completions`
- 202 polling endpoint: `GET https://integrate.api.nvidia.com/v1/status/{requestId}`
- content 배열의 영상 필드: `{"type":"video_url","video_url":{"url":"..."}}`
- 영상 확장자: MP4, MOV, WEBM 지원
- video/audio 요청은 system prompt에 `/no_think` 사용 필요
- 응답은 200 즉시 완료 또는 202 pending일 수 있고, 202는 `requestId`로 polling해야 한다.
- 공식 문서의 asset 안내: 이미지/오디오가 180KB를 초과하면 NVCF Asset API로 업로드한 뒤 asset ID를 참조해야 한다.
- 공식 infer 문서의 `NVCF-INPUT-ASSET-REFERENCES` header 설명에는 이미지/오디오가 180KB를 넘으면 presigned S3 URL로 업로드해야 한다고 되어 있고, header 길이는 370자 이하로 표시되어 있다.
- NVIDIA Developer Program 가입 후 API Catalog 접근 가능
- 이전 live smoke test에서 `GET /v1/models`는 200으로 성공했다.
- 이전 live smoke test에서 `nvidia/cosmos-reason2-8b`는 404 `Function ... Not found for account ...`를 반환했다. 이 계정에서는 Cosmos 계열을 hosted API로 바로 호출할 수 없고, self-host 배포가 필요한 후보로 취급한다.
- 2026-07-13 live smoke test에서 4초/44KB 샘플 MP4를 `nvidia/nemotron-3-nano-omni-30b-a3b-reasoning`으로 전송했을 때 200으로 즉시 완료됐다.
- 같은 live smoke test에서 응답은 `choices[0].message.content`에 JSON 문자열을 담았고, 현재 `observations/globalSummary` 정규화 로직으로 성공 처리됐다.
- 같은 live smoke test의 응답에는 `usage.prompt_tokens=920`, `usage.completion_tokens=197`, `usage.total_tokens=1117`이 포함됐다.
- 같은 live smoke test의 전체 호출 시간은 약 2.6초였고, 응답의 `nvext.request_throughput.e2e_latency_seconds`는 약 1.52초였다.
- 2026-07-13 NVCF Asset API live probe에서 `POST https://api.nvcf.nvidia.com/v2/nvcf/assets`는 200으로 `assetId`, `uploadUrl`, `contentType`, `description`을 반환했다.
- `uploadUrl` 업로드는 `PUT`이며, presigned URL의 signed header에 맞춰 `Content-Type`과 `x-amz-meta-nvcf-asset-description`을 함께 보내야 성공했다.
- asset 조회는 `GET https://api.nvcf.nvidia.com/v2/nvcf/assets/{assetId}`이며, 200 응답은 `asset.assetId`, `asset.description`, `asset.contentType`, `asset.createdAt` 구조였다.
- asset 삭제는 `DELETE https://api.nvcf.nvidia.com/v2/nvcf/assets/{assetId}`이며, 성공 시 204를 반환했다.
- 영상 asset을 content 배열의 `video_url.url = "data:video/mp4;asset_id,{assetId}"`로 참조하면 500 `Only base64 data URLs are supported for now`가 반환됐다.
- 영상 asset은 사용자 메시지 `content`를 문자열로 두고 `<video src="data:video/mp4;asset_id,{assetId}" />`를 포함하며, `NVCF-INPUT-ASSET-REFERENCES: {assetId}` header를 함께 보냈을 때 성공했다.
- 최종 구현 경로에서 25초/212KB public domain MP4 샘플을 `call_real_video_llm_model()`로 전송한 live 검증은 200으로 완료됐고, 응답은 기존 `observations/globalSummary` 스키마로 정규화됐다. 이 호출은 약 3.4초가 걸렸고 `usage.prompt_tokens=280`, `usage.completion_tokens=229`, `usage.total_tokens=509`를 반환했다.
- 2026-07-13 rate probe에서 4초/33KB synthetic MP4를 0.5초 간격으로 10회 연속 호출했지만 429는 발생하지 않았다. 10회 모두 200으로 즉시 완료됐고, `Retry-After` 또는 `X-RateLimit-*` header를 관찰할 기회는 없었다. 따라서 "10회 연속 호출은 허용됨"만 확인됐고, 실제 분당/일당 한도는 확인되지 않았다.
- 같은 rate probe에서 60초/486KB synthetic MP4는 asset 경로로 200 완료됐다. 1차 호출은 모델이 strict JSON을 깨서 파싱 실패했지만 HTTP/API 제한 오류는 아니었고, 재시도는 200/정규화 성공했다.
- 같은 날 Internet Archive의 공개 White House 영상 `youtube-OXo-XBvMAUQ`에서 60초 구간을 `/tmp`로 트림/다운스케일한 562KB MP4도 asset 경로로 200 완료됐다. 응답은 `usage.prompt_tokens=334`, `usage.completion_tokens=351`, `usage.total_tokens=685`, `nvext.request_throughput.e2e_latency_seconds=2.367778778076172`를 포함했고 기존 스키마로 정규화됐다.
- 검증 영상 출처: `https://archive.org/details/youtube-OXo-XBvMAUQ` / 원본 `https://www.youtube.com/watch?v=OXo-XBvMAUQ` / creator `The White House`. USAGov는 "government work"는 공식 직무로 만든 미국 정부 저작물이라고 설명하지만, 연방 웹사이트의 모든 자료가 정부 저작물은 아니며 제한이 있을 수 있다고도 안내한다. 따라서 이 파일은 저장소에 커밋하지 않고 `/tmp` 검증용으로만 사용했다.
- 2026-07-13 길이별 asset 경로 측정에서도 같은 공개 White House 발표 원본의 30초/60초/120초 구간을 `/tmp`에만 트림/다운스케일해 호출했다. 세 샘플 모두 NVCF Asset API 경로로 200 완료됐고, timeout 기본값 120초 대비 전체 소요 시간은 충분히 작았다.

| 샘플 | 파일 크기 | durationSec | asset 생성 | asset 업로드 | asset 삭제 | 전체 elapsed | prompt/completion/total tokens | 관찰 개수 | 시간 구간 품질 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| White House 30초 | 298,141 bytes | 30.25s | 233ms | 985ms | 716ms | 5,877ms | 335 / 596 / 931 | eyeContact 2, facialExpression 2, gesture 2, posture 1 | 일부 세부 구간을 나눴지만, 썸네일상 연단 발표 장면인데 "slide glance/pointing at slides"처럼 근거가 약한 라벨이 섞였다. |
| White House 60초 | 613,236 bytes | 60.25s | 156ms | 629ms | 793ms | 3,688ms | 335 / 368 / 703 | 각 category 1개 | 모든 관찰이 0~60.25s 전체 구간으로 반환됐다. 범위는 맞지만 시간 구간화 품질은 낮다. |
| White House 120초 | 1,208,423 bytes | 120.25s | 154ms | 1,138ms | 694ms | 3,995ms | 336 / 255 / 591 | 각 category 1개 | 모든 관찰이 0~120.25s 전체 구간으로 반환됐다. 범위는 맞지만 세부 변화 감지는 부족하다. |

이 측정에서는 파일 크기가 298KB에서 1.2MB로 늘어도 전체 지연이 선형으로 증가하지 않았다. 실제 병목은 asset 업로드보다 모델 응답 생성 변동에 더 가까워 보인다. 다만 샘플 수가 3개뿐이므로 운영 용량 추정에는 부족하다.

2026-07-13에 durationSec가 있을 때 프롬프트에 "초반/중반/후반 3구간으로 나누고, 각 category마다 구간별 관찰을 포함하라"는 지시를 추가한 뒤 같은 60초/120초 샘플을 다시 호출했다. `max_tokens`는 기존 1200을 유지했고, 가장 큰 completion은 1002 tokens라 잘림은 관찰되지 않았다.

| 샘플 | 프롬프트 | 전체 elapsed | prompt/completion/total tokens | 관찰 개수 | startSec/endSec 분포 | 품질 판단 |
| --- | --- | ---: | ---: | ---: | --- | --- |
| White House 60초 | 기존 | 3,688ms | 335 / 368 / 703 | 각 category 1개 | 모든 category가 0~60.25s 1개 | 범위는 맞지만 구간화 실패 |
| White House 60초 | 3구간 지시 | 11,750ms | 450 / 1002 / 1452 | 각 category 3개 | 0~20.083, 20.083~40.167, 40.167~60.25 | 구간화는 개선됐다. 다만 "slide glance"처럼 근거가 약한 라벨이 남았다. |
| White House 120초 | 기존 | 3,995ms | 336 / 255 / 591 | 각 category 1개 | 모든 category가 0~120.25s 1개 | 범위는 맞지만 구간화 실패 |
| White House 120초 | 3구간 지시 | 7,559ms | 451 / 942 / 1393 | 각 category 3개 | 0~40.083, 40.083~80.167, 80.167~120.25 | 구간화는 개선됐다. 일부 라벨은 실제 시각 변화보다 프롬프트 구조를 따른 추정일 가능성이 있다. |

토큰 증가폭은 60초 샘플에서 total 703 -> 1452, 120초 샘플에서 total 591 -> 1393이었다. completion token이 크게 늘었지만 현재 1200 output token 한도 안에는 들어왔다. 이 결과만 보면 3구간 프롬프트는 "전체 구간 하나로 뭉치는 문제"를 줄이는 데 효과가 있지만, 모델이 구간별로 실제로 다른 행동을 본 것인지, 요구된 형식을 채우기 위해 그럴듯한 라벨을 나눈 것인지는 추가 품질 검증이 필요하다.

### 아직 확인하지 못한 사실

- 무료 API의 정확한 rate limit. 공식 infer/status/createasset 문서에서 분당/일당 수치를 찾지 못했고, 실제 호출에서는 4초 영상 10회 연속 호출까지 429가 발생하지 않았다. **2026-07-20 웹 조사 추가**: NVIDIA가 공식 문서로 게시한 SLA는 아니지만, NVIDIA Developer Forums 게시물과 다수의 제3자 정리 글이 공통적으로 "API 키당 약 40 RPM(분당 요청 수), 계정 단위로 여러 모델에 걸쳐 공유"를 실무 기준선으로 보고한다. 이전에는 크레딧 기반 한도(개인 1000/기업 5000)가 있었으나 현재는 제거되고 순수 rate-limit 방식으로 보인다. NVIDIA는 요청만으로 한도를 올려주지 않으며, 클라이언트 측 exponential backoff + jitter, `Retry-After` 헤더 준수, 동시 요청 수 제한을 권장한다. 이 수치는 "모델·트래픽 상황에 따라 달라질 수 있다"는 NVIDIA 측 답변이 함께 보고되어 공식 확정치로 취급하면 안 된다. (출처: [decodethefuture.org 정리](https://decodethefuture.org/en/nvidia-nim-api-pricing-limits-guide/), [NVIDIA Developer Forums – rate limit 429 문의](https://forums.developer.nvidia.com/t/i-got-rate-limit-error-every-time-i-use-nvidia-nim-api/373385), [NVIDIA Developer Forums – rate limit 상향 요청](https://forums.developer.nvidia.com/t/request-for-nvidia-nim-api-rate-limit-increase-40-200-rpm/376561))
- 무료 API의 최대 영상 길이/용량 제한. 실제 호출에서는 120초/1.2MB MP4까지 200 성공했지만, 그 이상의 최대 길이/용량은 공식 문서나 오류 응답으로 확인하지 못했다.
- hosted API에서 `response_format: {"type":"json_object"}`를 실제 발표 영상과 다양한 입력에서도 안정적으로 따르는지 여부
- 실제 사람이 등장하는 발표 영상에서 관찰 품질이 충분한지 여부
- asset 경로 응답의 시간 구간 품질. 3구간 프롬프트 적용 후 60초/120초 샘플 모두 구간화는 개선됐지만, 일부 라벨은 실제 시각 변화보다 프롬프트 구조를 따른 추정일 가능성이 있다.

위 항목은 추측하면 안 된다. 현재 구현은 180KB 이하 영상은 기존 `video_url` base64 data URL로 보내고, 180KB 초과 영상은 NVCF Asset API 업로드 후 HTML-style `<video>` asset reference 문자열로 자동 전환한다.

### 장점

- 개발 단계에서 Prototype/free hosted API endpoint로 PoC를 시작할 수 있다.
- OpenAI-compatible `/v1/chat/completions` 형태라 기존 외부 LLM 호출 관례와 비슷하게 통합할 수 있다.
- 네이티브 `.mp4` 입력을 받는 모델이므로 프레임 샘플링 방식보다 영상 흐름 이해에 유리할 가능성이 있다.
- 자체 GPU 서버 없이 외부 API로 시작할 수 있다.
- 실패 시 기존 `FALLBACK` mock 정책을 유지할 수 있다.

### 단점

- 정확한 무료 quota/rate limit을 아직 확인하지 못했다. 다만 4초 영상 10회 연속 호출에서는 429가 발생하지 않았다.
- 영상 길이/용량 제한을 아직 확인하지 못했다. 다만 120초/1.2MB MP4 asset 경로 호출은 200으로 성공했다.
- 180KB를 넘는 실제 발표 영상은 NVCF Asset API 업로드/삭제 경로를 사용한다.
- 모델이 항상 요청한 JSON schema를 지키는지는 라이브 검증이 필요하다.
- 새 벤더 키(`NVIDIA_API_KEY`)와 운영 설정이 추가된다.
- 무료 엔드포인트는 운영 SLA나 장기 안정성 측면에서 유료/엔터프라이즈 계약과 다를 수 있다.

### 적합한 경우

- 사용자가 NVIDIA Developer Program/API Catalog를 통해 무료 PoC를 먼저 해보고 싶은 경우
- 프레임 샘플링보다 네이티브 영상 이해 모델을 우선 검증하고 싶은 경우
- 자체 GPU 서버 없이 외부 hosted endpoint로 시작하고 싶은 경우
- 실제 API 키 발급 후 rate limit, 길이 제한, 응답 품질을 직접 확인할 수 있는 경우

## 6. 비교 표

| 기준 | 옵션 A: OpenAI GPT-4o vision | 옵션 B: Google Gemini native video | 옵션 C: 오픈소스 로컬 모델 | 옵션 D: NVIDIA Nemotron omni-modal reasoning |
| --- | --- | --- | --- | --- |
| 입력 방식 | 샘플 프레임 이미지 + 타임스탬프 | 영상 파일/영상 데이터 직접 입력 | 프레임 또는 영상 입력, 모델별 상이 | 180KB 이하는 `video_url` base64 data URL, 초과 영상은 NVCF Asset API + `<video src="data:video/mp4;asset_id,{assetId}" />` |
| 통합 난이도 | 낮음 | 중간~높음 | 높음 | 중간 |
| 비용 구조 | 이미지/텍스트 토큰 기반. 프레임 수 상한으로 예측 쉬움 | 영상 길이/토큰 기반. 긴 영상일수록 비용 증가 | API 비용 없음. 대신 GPU 서버/운영 비용 발생 | 무료 개발 엔드포인트 가능. 4초 영상 10회 연속 호출은 429 없이 성공했지만 정확한 quota/rate limit은 확인 필요 |
| 비용 예측 가능성 | 높음 | 중간~낮음 | 인프라 고정비 중심. 사용량이 적으면 비효율 가능 | 현재는 낮음. 120초/1.2MB asset 호출 성공은 확인했지만 무료 제한과 유료 전환 조건 확인 필요 |
| 응답 품질 특성 | 샘플 프레임 기반 관찰. 짧은 변화 누락 가능 | 시간 흐름을 포함한 네이티브 영상 이해 가능 | 모델/서빙 품질에 크게 의존 | 네이티브 영상 이해/structured reasoning을 표방. 실제 발표 영상 품질 검증 필요 |
| 기존 OpenAI 인프라 호환성 | 높음 | 낮음 | 낮음 | `/v1/chat/completions` 형태는 유사하나 키/벤더/NVCF Asset API가 별도 필요 |
| 현재 프레임 샘플링 로직 재사용 | 쉬움 | 필수는 아님 | 모델 방식에 따라 가능 | 필수는 아님. 영상 파일 직접 전달 방향 |
| 인프라 요구사항 | 기존 OpenAI API 키/HTTP 호출 확장 | Gemini API 키, 파일 업로드/SDK/과금 관리 | GPU, 모델 서버, 큐/메모리/모니터링 | NVIDIA API key, hosted endpoint 설정 |
| 운영 리스크 | 이미지 토큰 비용, 프롬프트 품질 | 벤더 추가, 긴 영상 비용, timeout | GPU 장애, 메모리 부족, 추론 지연, 모델 관리 | 무료 quota 불명확, asset cleanup 실패 가능성, timeout/202 polling |
| 초기 mock 제거 속도 | 가장 빠름 | 중간 | 가장 느림 | 키만 확보되면 빠른 편. 단 첫 라이브 검증 필요 |
| 장기 확장성 | API 비용 증가에 주의 | API 비용 증가에 주의 | 인프라를 잘 갖추면 유리할 수 있음 | 무료 개발 이후 운영 조건 확인 필요 |

## 7. 권장 사항과 트레이드오프

최종 결정은 사용자가 어떤 우선순위를 두는지에 따라 달라진다.

기존 인프라 재사용성과 비용 예측 가능성을 우선하면 **옵션 A: OpenAI GPT-4o vision**이 가장 낮은 통합 비용을 가진다. 이미 OpenAI 키, timeout, 토큰 사용량 로깅, `REAL/MOCK/FALLBACK`, 재시도 중복 호출 방지 정책이 backend에 있으므로, 같은 운영 철학을 `video-llm-engine`으로 확장하기 쉽다. 또한 프레임 개수 상한으로 비용을 제한할 수 있어 초기 서비스 운영에 유리하다.

반대로 응답 품질에서 "프레임 몇 장을 본 분석"이 아니라 "영상 흐름을 본 분석"이 중요하다면 **옵션 B: Google Gemini**가 더 설득력 있다. 시선 변화, 제스처 전환, 자세 흐름처럼 시간성이 중요한 관찰은 네이티브 영상 입력 모델이 더 잘 잡을 가능성이 있다. 다만 새 벤더 통합과 길이 비례 비용 통제 장치가 필요하다.

**옵션 C: 오픈소스 로컬 모델**은 장기적으로 독립성과 데이터 통제 측면에서 매력적이지만, 현재 프로젝트 단계에서는 GPU/서빙/성능 검증 부담이 크다. 실제 GPU 인프라가 준비되어 있지 않다면 첫 번째 실제 모델 도입 옵션으로는 리스크가 크다.

**옵션 D: NVIDIA Nemotron omni-modal reasoning**은 사용자가 무료 hosted API로 네이티브 영상 이해 PoC를 원한다는 조건에서 현재 가장 직접적인 후보다. 공식 API 문서에서 `video_url` content, `/v1/chat/completions`, 202 polling, `/no_think` 요구사항이 확인되어 `video-llm-engine`의 real-call 함수 안에 격리해 붙이기 좋다. 큰 영상은 NVCF Asset API를 통해 먼저 업로드한 뒤, 확인된 HTML-style `<video>` asset reference 방식으로 전송한다. 2026-07-13 기준 4초 영상 10회 연속 호출과 120초/1.2MB asset 호출은 모두 rate/length 제한에 걸리지 않았다. 다만 정확한 무료 rate limit과 최대 영상 길이/용량은 공식 수치 또는 429/제한 오류로 아직 확인되지 않았다.

따라서 "무료 네이티브 영상 이해 PoC"가 목표라면 옵션 D를 먼저 구현/검증하고, 라이브 호출에서 payload/limit 문제가 크면 옵션 A 또는 B로 비교 실험하는 순서가 현실적이다.

## 8. 어떤 옵션을 선택해도 필요한 공통 작업

1. **응답 스키마 고정**
   - backend의 `VideoLlmEngineResponse` 계약을 유지한다.
   - 모델 응답은 반드시 `jobId/status/model/observations/globalSummary`로 정규화한다.

2. **generationMode 도입**
   - OpenAI 피드백과 동일하게 `REAL/MOCK/FALLBACK` 패턴을 `video-llm-engine`에도 도입한다.
   - 실제 모델 호출 성공 시 `REAL`, 실패 후 mock 대체 시 `FALLBACK`, 개발용 고정 응답은 `MOCK`으로 구분한다.

3. **실패 폴백**
   - 모델 호출 실패가 전체 분석 실패로 바로 이어질지, 기존 mock 형태로 폴백할지 정책을 정한다.
   - 초기 서비스 안정성 관점에서는 `FALLBACK`으로 분석 흐름을 유지하되, 결과 화면에 실제 모델 분석이 아니라는 신호를 남기는 방식이 안전하다.

4. **응답 검증**
   - 모델이 누락한 필드, 잘못된 타입, 깨진 JSON을 반환해도 backend 저장/병합이 깨지지 않도록 `video-llm-engine` 내부에서 스키마 검증을 수행한다.

5. **비용/지연시간 로깅**
   - 실제 모델 호출 시간, 입력 프레임 수 또는 영상 길이, 입력/출력 토큰, generationMode를 로그로 남긴다.
   - 가능하면 `OPENAI_USAGE`, `OPENAI_REUSE`와 비슷하게 grep 가능한 접두어를 둔다.

6. **중복 호출 방지**
   - 같은 jobId의 `REAL` Video LLM 결과가 이미 있으면 재시도 시 재사용할지 정책을 정한다.
   - OpenAI 단계처럼 비용이 큰 외부 호출은 재시도 중복 호출을 막는 것이 좋다.

7. **테스트**
   - 정상 응답, 모델 실패 후 폴백, 깨진 JSON, schema 누락, 긴 영상/프레임 상한, 인증 헤더 누락을 pytest로 검증한다.

## 9. 다음 결정 질문

다음 구현 Unit에 들어가기 전에 아래를 정해야 한다.

1. 첫 PoC 목표가 **빠른 mock 제거**인지, **최고 영상 이해 품질 검증**인지.
2. 발표 영상 최대 길이를 얼마로 제한할지.
3. 1개 분석 job당 허용 가능한 최대 모델 비용을 얼마로 볼지.
4. 영상/프레임 데이터를 외부 벤더 API로 보내도 되는지.
5. 실패 시 결과를 `FALLBACK`으로 계속 생성할지, 아니면 분석 실패로 처리할지.

옵션 D 관점의 현재 답은 다음과 같다.

1. 첫 PoC 목표는 **무료 네이티브 영상 이해 모델로 mock 제거 가능성 검증**이다.
2. 발표 영상 최대 길이는 현재 관찰된 120초 성공을 임시 기준으로 삼을 수 있지만, 실제 최대 길이는 NVIDIA 무료 엔드포인트 제한을 추가 확인한 뒤 정해야 한다.
3. 1개 job당 비용은 무료 tier에서 시작하되, rate limit/quota는 아직 공식 수치가 없으므로 운영 전 추가 확인해야 한다.
4. 영상 데이터를 NVIDIA API로 보내는 데 동의해야 한다.
5. 실패 시에는 현재처럼 `FALLBACK` mock을 생성해 전체 분석 흐름은 유지한다.

이 질문에 대한 답과 NVIDIA 라이브 검증 결과에 따라 옵션 A/B/C/D의 우선순위가 달라진다.

## 10. 2026-07-20 구현: 영상 구간 분할(chunking)

Video LLM 실제 모델 활성화 결정 체크리스트 검토 중, 위 2번 질문(영상 최대 길이가
실측 성공 범위 120초보다 훨씬 길다는 문제)에 대한 답으로 영상 구간 분할을
구현했다. `video-llm-engine/app/api/video_llm_analysis.py`의
`call_real_video_llm_model()`은 `request.durationSec`가
`VIDEO_LLM_CHUNK_DURATION_SECONDS`(기본 100초, 안전 구간 120초보다 여유를 둠)를
넘으면 `call_real_video_llm_model_in_chunks()`로 분기한다. `imageio-ffmpeg`(이미
analysis-engine이 쓰던 것과 동일한 방식, 시스템 ffmpeg 설치 불필요)로 원본을
`-c copy`(재인코딩 없이) 구간 길이만큼 실제로 잘라, 세그먼트마다 독립적으로 NVIDIA를
호출한 뒤 시간 오프셋을 적용해 하나의 응답으로 합친다. 세그먼트 일부가 실패해도
나머지로 계속 진행하고, 전부 실패했을 때만 기존 `FALLBACK` mock 경로로 넘어간다.

프롬프트 지시만으로 긴 타임라인을 구간화하게 했던 기존 3구간 프롬프트 방식과 달리,
이번에는 모델이 실제로 그 구간의 영상만 보고 답하므로 "실제 시각 변화보다 프롬프트
구조를 따른 추정"일 가능성이 구조적으로 줄어들 것으로 기대한다. 다만 이 기대는 아직
실제 NVIDIA 응답으로 검증되지 않았다(아래 한계 참고).

**아직 확인하지 못한 사실(실제 NVIDIA API 키 필요)**:
- 세그먼트 단위 호출이 실제로 시간 구간화 품질을 개선하는지. 라이브 검증
  (2026-07-20, 가짜 API 키로 30.13초 실제 영상을 10초 단위 4세그먼트로 분할)은 ffmpeg
  분할과 NVIDIA 네트워크 경로(실제 403 응답 수신)까지만 확인했고, 진짜 모델 응답
  품질은 확인하지 못했다.
- 세그먼트 수만큼 늘어난 호출량이 무료 rate limit(약 40 RPM으로 보고됨)에 실제로
  어떤 영향을 주는지.

**알려진 설계상 한계**: backend의 `video-llm-daily`/`video-llm-monthly` 예산
카운터는 "작업(job) 1건"당 1을 소비하는 구조인데, 구간 분할이 켜지면 작업 1건이
내부적으로 여러 번 NVIDIA를 호출한다(예: 30분짜리 영상은 기본 설정에서 최대
18세그먼트). 즉 실제 NVIDIA 호출량이 이 예산 카운터가 나타내는 값보다 훨씬 클 수
있다. 실제 사용 데이터를 확보하면 카운터를 세그먼트 단위로 바꿀지, 아니면 현재처럼
작업 단위로 유지하되 값만 재조정할지 결정해야 한다.

## 11. 2026-07-20 구현: 짧은 구간에는 3구간 강제 분할 프롬프트를 쓰지 않음

체크리스트 3번 질문("시간 구간화 품질 한계를 사용자에게 어떻게 노출할지")에 대해
사용자가 "프롬프트/후처리 개선 먼저 시도"를 선택했다. 기존 3구간 강제 프롬프트는
60초/120초처럼 비교적 긴 단일 호출 영상에서 모델이 관찰을 `[0, duration]` 하나로
뭉개버리는 문제에 대응하려고 도입한 것이었다(5절 측정 표 참고). 그런데 10절의
구간 분할(chunking)을 도입한 뒤에는 이 프롬프트가 짧은 세그먼트(예: 10~20초)에도
그대로 적용되어, 실제로는 하나로 이어지는 행동을 억지로 3조각으로 쪼개 답하게
만들 위험이 생겼다.

`build_duration_prompt()`가 `MIN_DURATION_FOR_FORCED_SEGMENTATION_SEC`(기본
30초) 미만이면 3구간 강제 지시 없이 "실제로 관찰한 시점을 그대로, 진짜 하나로
이어지는 행동이면 하나의 관찰로" 보고하라는 프롬프트를 쓰도록 바꿨다. 30초
이상은 기존에 라이브 검증된 3구간 강제 프롬프트를 그대로 유지한다.

**아직 확인하지 못한 사실(실제 NVIDIA API 키 필요)**: 이 프롬프트 변경이 실제로
짧은 세그먼트의 관찰 품질을 개선하는지는 실제 모델 응답으로 확인해야 한다. 현재는
"모델이 실제로 그 구간만 보고 답하는 것이 더 정직할 것"이라는 설계상 추론일 뿐,
라이브 검증되지 않았다.
