import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import AnalysisTimelineSection from "./AnalysisTimelineSection";

describe("AnalysisTimelineSection", () => {
    it("renders quantitative and real Video LLM tracks and seeks by timestamp", () => {
        const onSeekToTime = vi.fn();

        render(
            <AnalysisTimelineSection
                durationSec={100}
                currentTimeSec={25}
                sttSegments={[{ start: 10, end: 20, text: "첫 번째 발화" }]}
                poseFrameResults={[{
                    timestampSec: 30,
                    poseDetected: true,
                    shoulderBalanceScore: 86,
                }]}
                gestureFrameResults={[{
                    timestampSec: 40,
                    gestureDetected: true,
                }]}
                visualAnalysis={{
                    model: { generationMode: "REAL" },
                    observations: {
                        posture: [{ startSec: 50, endSec: 60, description: "안정적인 자세" }],
                    },
                }}
                notableMoments={[{
                    timestampSec: 70,
                    label: "자세 균형이 흔들린 순간",
                }]}
                onSeekToTime={onSeekToTime}
            />
        );

        expect(screen.getAllByText("실제 Video LLM")).toHaveLength(2);
        expect(screen.queryByText(/예시 데이터/)).not.toBeInTheDocument();

        fireEvent.click(screen.getByRole("button", {
            name: /발화 00:10 구간으로 이동/,
        }));
        expect(onSeekToTime).toHaveBeenCalledWith(10);

        fireEvent.click(screen.getByRole("button", {
            name: /주요 순간 01:10 지점으로 이동/,
        }));
        expect(onSeekToTime).toHaveBeenCalledWith(70);
    });

    it("warns when Video LLM observations are mock fallback data", () => {
        render(
            <AnalysisTimelineSection
                durationSec={60}
                visualAnalysis={{
                    model: { generationMode: "FALLBACK" },
                    observations: {
                        gesture: [{ startSec: 5, endSec: 45, description: "샘플 관찰" }],
                    },
                }}
            />
        );

        expect(screen.getAllByText("Mock 대체")).toHaveLength(2);
        expect(screen.getByRole("note")).toHaveTextContent("예시 데이터");
    });

    it("does not render an empty timeline", () => {
        const { container } = render(<AnalysisTimelineSection durationSec={60} />);
        expect(container).toBeEmptyDOMElement();
    });
});
