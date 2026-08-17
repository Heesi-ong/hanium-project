import { useCallback, useEffect, useRef, useState } from "react";

// UploadPage와 ResultDetailPage 둘 다 분석 상태(getAnalysisStatus)를
// 반복 조회하며 경과 시간 타임아웃과 종료 상태(COMPLETED/FAILED/CANCELLED) 판별
// 로직을 각자 구현하고 있었습니다. 다만 두 페이지는 연속 실패 허용 범위(UploadPage는
// 즉시 중단, ResultDetailPage는 5회까지 허용)와 종료 상태별로 할 일(토스트+이동 vs
// 결과 재조회)이 서로 달라, 그 부분까지 하나로 합치면 동작이 바뀝니다. 그래서 여기서는
// 경과시간/연속실패 카운트 같은 뼈대만 공유하고, 종료 상태별 동작은 호출자가 콜백으로
// 그대로 주입하게 했습니다. 다음 요청은 현재 요청과 콜백 처리가 끝난 뒤 setTimeout으로
// 예약해 느린 응답 중 폴링이 겹치지 않게 합니다.
export function useJobStatusPolling({
    intervalMs,
    timeoutMs,
    maxConsecutiveFailures = 1,
    fetchStatus,
    onStatus,
    onCompleted,
    onFailed,
    onCancelled,
    onTimeout,
    onPollError,
}) {
    const timerRef = useRef(null);
    const runIdRef = useRef(0);
    const startedAtRef = useRef(null);
    const failureCountRef = useRef(0);
    const handlersRef = useRef({
        fetchStatus,
        onStatus,
        onCompleted,
        onFailed,
        onCancelled,
        onTimeout,
        onPollError,
    });
    const [polling, setPolling] = useState(false);

    // 페이지의 상태 변경으로 콜백 함수가 다시 만들어져도 이미 실행 중인 타이머와
    // startPolling 함수 정체성은 유지하고, 실제 호출 시점에는 최신 콜백을 사용합니다.
    useEffect(() => {
        handlersRef.current = {
            fetchStatus,
            onStatus,
            onCompleted,
            onFailed,
            onCancelled,
            onTimeout,
            onPollError,
        };
    }, [fetchStatus, onStatus, onCompleted, onFailed, onCancelled, onTimeout, onPollError]);

    const stopPolling = useCallback(() => {
        runIdRef.current += 1;
        if (timerRef.current) {
            clearTimeout(timerRef.current);
            timerRef.current = null;
        }

        startedAtRef.current = null;
        failureCountRef.current = 0;
        setPolling(false);
    }, []);

    const startPolling = useCallback(
        (jobId) => {
            stopPolling();

            const runId = runIdRef.current + 1;
            runIdRef.current = runId;
            startedAtRef.current = Date.now();
            failureCountRef.current = 0;
            setPolling(true);

            const poll = async () => {
                if (runIdRef.current !== runId) {
                    return;
                }

                try {
                    const elapsedMs = Date.now() - startedAtRef.current;

                    if (elapsedMs > timeoutMs) {
                        stopPolling();
                        handlersRef.current.onTimeout?.();
                        return;
                    }

                    const statusData = await handlersRef.current.fetchStatus(jobId);
                    if (runIdRef.current !== runId) {
                        return;
                    }
                    failureCountRef.current = 0;
                    await handlersRef.current.onStatus?.(statusData);

                    if (statusData.status === "COMPLETED") {
                        stopPolling();
                        await handlersRef.current.onCompleted?.(statusData);
                        return;
                    }

                    if (["FAILED", "DEAD_LETTER"].includes(statusData.status)) {
                        stopPolling();
                        await handlersRef.current.onFailed?.(statusData);
                        return;
                    }

                    if (statusData.status === "CANCELLED") {
                        stopPolling();
                        await handlersRef.current.onCancelled?.(statusData);
                        return;
                    }
                } catch (requestError) {
                    if (runIdRef.current !== runId) {
                        return;
                    }
                    failureCountRef.current += 1;

                    if (failureCountRef.current >= maxConsecutiveFailures) {
                        stopPolling();
                        handlersRef.current.onPollError?.(requestError);
                        return;
                    }
                    // 임계치 미만이면 다음 폴링 주기에 자동 재시도합니다.
                }

                if (runIdRef.current === runId) {
                    timerRef.current = setTimeout(poll, intervalMs);
                }
            };

            timerRef.current = setTimeout(poll, intervalMs);
        },
        [
            intervalMs,
            timeoutMs,
            maxConsecutiveFailures,
            stopPolling,
        ]
    );

    return { polling, startPolling, stopPolling };
}
