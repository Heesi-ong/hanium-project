import { useCallback, useRef, useState } from "react";

// UploadPage와 ResultDetailPage 둘 다 setInterval로 분석 상태(getAnalysisStatus)를
// 반복 조회하며 경과 시간 타임아웃과 종료 상태(COMPLETED/FAILED/CANCELLED) 판별
// 로직을 각자 구현하고 있었습니다. 다만 두 페이지는 연속 실패 허용 범위(UploadPage는
// 즉시 중단, ResultDetailPage는 5회까지 허용)와 종료 상태별로 할 일(토스트+이동 vs
// 결과 재조회)이 서로 달라, 그 부분까지 하나로 합치면 동작이 바뀝니다. 그래서 여기서는
// setInterval/경과시간/연속실패 카운트 같은 뼈대만 공유하고, 종료 상태별 동작은 호출자가
// 콜백으로 그대로 주입하게 했습니다.
export function useJobStatusPolling({
    intervalMs,
    timeoutMs,
    maxConsecutiveFailures = 1,
    fetchStatus,
    onCompleted,
    onFailed,
    onCancelled,
    onTimeout,
    onPollError,
}) {
    const timerRef = useRef(null);
    const startedAtRef = useRef(null);
    const failureCountRef = useRef(0);
    const [polling, setPolling] = useState(false);

    const stopPolling = useCallback(() => {
        if (timerRef.current) {
            clearInterval(timerRef.current);
            timerRef.current = null;
        }

        startedAtRef.current = null;
        failureCountRef.current = 0;
        setPolling(false);
    }, []);

    const startPolling = useCallback(
        (jobId) => {
            stopPolling();

            startedAtRef.current = Date.now();
            failureCountRef.current = 0;
            setPolling(true);

            timerRef.current = setInterval(async () => {
                try {
                    const elapsedMs = Date.now() - startedAtRef.current;

                    if (elapsedMs > timeoutMs) {
                        stopPolling();
                        onTimeout?.();
                        return;
                    }

                    const statusData = await fetchStatus(jobId);
                    failureCountRef.current = 0;

                    if (statusData.status === "COMPLETED") {
                        stopPolling();
                        await onCompleted?.(statusData);
                        return;
                    }

                    if (statusData.status === "FAILED") {
                        stopPolling();
                        await onFailed?.(statusData);
                        return;
                    }

                    if (statusData.status === "CANCELLED") {
                        stopPolling();
                        await onCancelled?.(statusData);
                    }
                } catch (requestError) {
                    failureCountRef.current += 1;

                    if (failureCountRef.current >= maxConsecutiveFailures) {
                        stopPolling();
                        onPollError?.(requestError);
                    }
                    // 임계치 미만이면 다음 폴링 주기에 자동 재시도합니다.
                }
            }, intervalMs);
        },
        [
            intervalMs,
            timeoutMs,
            maxConsecutiveFailures,
            fetchStatus,
            onCompleted,
            onFailed,
            onCancelled,
            onTimeout,
            onPollError,
            stopPolling,
        ]
    );

    return { polling, startPolling, stopPolling };
}
