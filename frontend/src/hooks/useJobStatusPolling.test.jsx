import { act, renderHook } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { useJobStatusPolling } from "./useJobStatusPolling";

describe("useJobStatusPolling", () => {
    afterEach(() => {
        vi.useRealTimers();
    });

    it("stops polling and reports DEAD_LETTER as a failed terminal status", async () => {
        vi.useFakeTimers();
        const statusData = {
            jobId: "20260809123456-a1b2c3d4",
            status: "DEAD_LETTER",
            failReason: "재시도 횟수를 소진했습니다.",
        };
        const onFailed = vi.fn();
        const { result } = renderHook(() => useJobStatusPolling({
            intervalMs: 100,
            timeoutMs: 1000,
            fetchStatus: vi.fn().mockResolvedValue(statusData),
            onFailed,
        }));

        act(() => {
            result.current.startPolling(statusData.jobId);
        });
        await act(async () => {
            await vi.advanceTimersByTimeAsync(100);
        });

        expect(onFailed).toHaveBeenCalledWith(statusData);
        expect(result.current.polling).toBe(false);
    });

    it("keeps startPolling stable when caller callbacks change", () => {
        const { result, rerender } = renderHook(
            ({ onCompleted }) => useJobStatusPolling({
                intervalMs: 100,
                timeoutMs: 1000,
                fetchStatus: vi.fn(),
                onCompleted,
            }),
            { initialProps: { onCompleted: vi.fn() } }
        );
        const initialStartPolling = result.current.startPolling;

        rerender({ onCompleted: vi.fn() });

        expect(result.current.startPolling).toBe(initialStartPolling);
    });

    it("recovers after four consecutive status failures without stopping polling", async () => {
        vi.useFakeTimers();
        const recoveredStatus = {
            jobId: "20260809123456-a1b2c3d4",
            status: "BASIC_ANALYZING",
        };
        const fetchStatus = vi.fn()
            .mockRejectedValueOnce({ error: "SERVICE_UNAVAILABLE" })
            .mockRejectedValueOnce({ error: "SERVICE_UNAVAILABLE" })
            .mockRejectedValueOnce({ error: "SERVICE_UNAVAILABLE" })
            .mockRejectedValueOnce({ error: "SERVICE_UNAVAILABLE" })
            .mockResolvedValue(recoveredStatus);
        const onStatus = vi.fn();
        const onPollError = vi.fn();
        const { result } = renderHook(() => useJobStatusPolling({
            intervalMs: 100,
            timeoutMs: 2000,
            maxConsecutiveFailures: 5,
            fetchStatus,
            onStatus,
            onPollError,
        }));

        act(() => {
            result.current.startPolling(recoveredStatus.jobId);
        });
        await act(async () => {
            await vi.advanceTimersByTimeAsync(500);
        });

        expect(fetchStatus).toHaveBeenCalledTimes(5);
        expect(onStatus).toHaveBeenCalledWith(recoveredStatus);
        expect(onPollError).not.toHaveBeenCalled();
        expect(result.current.polling).toBe(true);

        act(() => {
            result.current.stopPolling();
        });
    });
});
