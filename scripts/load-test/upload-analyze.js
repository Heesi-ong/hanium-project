// k6 부하 테스트: 로그인 → 영상 업로드 → 분석 실행 → 상태 폴링(→ 결과 조회)까지의
// 실제 사용자 흐름을 재현합니다. 목적은 "동시 업로드가 몰릴 때 큐 상한/백프레셔/워커
// 처리량이 설계대로 동작하는지"를 수치로 확인하는 것입니다.
//
// 실행 예시:
//   BASE_URL=http://localhost:8080 \
//   LOAD_EMAIL=loadtest@example.com LOAD_PASSWORD='LoadTest!2026aB' \
//   k6 run scripts/load-test/upload-analyze.js
//
// 처음 실행 전, 위 계정으로 한 번 회원가입해 두거나 AUTO_SIGNUP=true 로 두면 VU마다
// 고유 계정을 자동 생성합니다. (운영 DB가 아니라 반드시 dev/staging 환경에서 돌리세요.)
//
// 주의: 이 테스트는 실제 분석 파이프라인(mock/real video-llm + OpenAI)을 돌립니다.
// 운영 환경이나 실제 비용이 발생하는 설정(OPENAI_ENABLED=true, VIDEO_LLM_ENABLED=true)
// 에서 무겁게 돌리지 마세요. 기본값은 소규모(smoke)입니다.

import http from "k6/http";
import { check, sleep } from "k6";
import { Counter, Trend } from "k6/metrics";

// 큐 백프레셔의 429는 이 시나리오에서 예상된 방어 응답입니다. 별도 카운터에는 기록하되
// k6 기본 http_req_failed 비율에는 포함하지 않아 정상 방어가 5xx 장애로 집계되지 않게 합니다.
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 399 }, 429));

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const AUTO_SIGNUP = (__ENV.AUTO_SIGNUP || "false").toLowerCase() === "true";
const EMAIL = __ENV.LOAD_EMAIL || "loadtest@example.com";
const PASSWORD = __ENV.LOAD_PASSWORD || "LoadTest!2026aB";
const VIDEO_PATH = __ENV.VIDEO_PATH || "../../sample-demo.mp4";
// 분석 완료까지 최대 몇 초 폴링할지. 초과하면 미완료로 기록(실패가 아니라 관측치).
const MAX_POLL_SECONDS = Number(__ENV.MAX_POLL_SECONDS || 180);
const POLL_INTERVAL_SECONDS = Number(__ENV.POLL_INTERVAL_SECONDS || 3);

// 업로드할 샘플 영상을 한 번만 읽어 모든 VU가 공유합니다(메모리 절약).
const videoBin = open(VIDEO_PATH, "b");

const uploadRejected = new Counter("upload_rejected_429"); // 백프레셔로 거부된 업로드
const runRejected = new Counter("run_rejected_429"); // 큐 상한으로 거부된 실행
const analysisCompleted = new Counter("analysis_completed");
const analysisTimedOut = new Counter("analysis_poll_timeout");
const analysisFailed = new Counter("analysis_failed");
const timeToComplete = new Trend("analysis_time_to_complete_seconds", true);

export const options = {
    // 기본은 아주 가벼운 smoke. 진짜 부하는 STAGES 환경변수나 아래 값을 조정하세요.
    scenarios: {
        upload_analyze: {
            executor: "ramping-vus",
            startVUs: 0,
            stages: [
                { duration: __ENV.RAMP_UP || "30s", target: Number(__ENV.VUS || 3) },
                { duration: __ENV.HOLD || "1m", target: Number(__ENV.VUS || 3) },
                { duration: "15s", target: 0 },
            ],
            gracefulStop: "30s",
        },
    },
    thresholds: {
        // 서버 오류(5xx)는 0에 가까워야 합니다. 429(백프레셔)는 실패가 아니라 정상 방어입니다.
        http_req_failed: ["rate<0.05"],
        // 로그인/업로드 요청 응답 시간(대기). 분석 폴링은 별도 Trend로 봅니다.
        http_req_duration: ["p(95)<5000"],
    },
};

function unwrap(res) {
    try {
        const body = res.json();
        // 백엔드 공통 응답이 { data: ... } 또는 { success, data } 형태일 수 있어 둘 다 대응.
        if (body && typeof body === "object" && "data" in body) {
            return body.data;
        }
        return body;
    } catch (e) {
        return null;
    }
}

function ensureLoggedIn() {
    // k6는 VU별 쿠키 자(jar)를 자동 관리하므로, 로그인 성공 시 httpOnly 세션 쿠키가 유지됩니다.
    let email = EMAIL;
    if (AUTO_SIGNUP) {
        email = `loadtest+${__VU}-${Date.now()}@example.com`;
        http.post(
            `${BASE_URL}/api/auth/signup`,
            JSON.stringify({ email, password: PASSWORD, agreedToTerms: true }),
            { headers: { "Content-Type": "application/json" } }
        );
    }

    const res = http.post(
        `${BASE_URL}/api/auth/login`,
        JSON.stringify({ email, password: PASSWORD }),
        { headers: { "Content-Type": "application/json" }, tags: { name: "login" } }
    );
    check(res, { "login 200": (r) => r.status === 200 });
    return res.status === 200;
}

export default function () {
    if (!ensureLoggedIn()) {
        sleep(1);
        return;
    }

    // 1) 업로드 (multipart). 429면 백프레셔로 카운트하고 다음 반복으로 넘어갑니다.
    const uploadRes = http.post(
        `${BASE_URL}/api/analysis/upload`,
        { file: http.file(videoBin, "sample-demo.mp4", "video/mp4") },
        { tags: { name: "upload" }, timeout: "120s" }
    );
    if (uploadRes.status === 429) {
        uploadRejected.add(1);
        sleep(2);
        return;
    }
    if (!check(uploadRes, { "upload 2xx": (r) => r.status >= 200 && r.status < 300 })) {
        sleep(1);
        return;
    }
    const uploaded = unwrap(uploadRes);
    const jobId = uploaded && (uploaded.jobId || uploaded.id);
    if (!jobId) {
        sleep(1);
        return;
    }

    // 2) 분석 실행. 큐 상한 초과 시 429 → 정상 방어로 카운트.
    const runRes = http.post(
        `${BASE_URL}/api/analysis/${jobId}/run`,
        JSON.stringify({ useVideoLlm: true, useOpenAi: false }),
        { headers: { "Content-Type": "application/json" }, tags: { name: "run" } }
    );
    if (runRes.status === 429) {
        runRejected.add(1);
        sleep(2);
        return;
    }
    check(runRes, { "run 2xx": (r) => r.status >= 200 && r.status < 300 });

    // 3) 상태 폴링. 완료/실패/타임아웃까지 관측.
    const startedAt = Date.now();
    let terminal = false;
    while ((Date.now() - startedAt) / 1000 < MAX_POLL_SECONDS) {
        sleep(POLL_INTERVAL_SECONDS);
        const statusRes = http.get(`${BASE_URL}/api/analysis/${jobId}/status`, {
            tags: { name: "status" },
        });
        if (statusRes.status !== 200) {
            continue;
        }
        const status = unwrap(statusRes);
        const state = status && (status.status || status.state);
        if (state === "COMPLETED") {
            analysisCompleted.add(1);
            timeToComplete.add((Date.now() - startedAt) / 1000);
            terminal = true;
            break;
        }
        if (state === "FAILED" || state === "CANCELLED") {
            analysisFailed.add(1);
            terminal = true;
            break;
        }
    }
    if (!terminal) {
        analysisTimedOut.add(1);
    }

    sleep(1);
}
