import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import SttSection from "./SttSection";

const baseSttInfo = {
    success: true,
    modelSize: "small",
    language: "ko",
    languageProbability: 0.98,
    segmentCount: 2,
    transcript: "안녕하세요 발표를 시작하겠습니다.",
};

const segments = [
    { start: 0, end: 3.5, duration: 3.5, text: "안녕하세요" },
    { start: 3.5, end: 7.2, duration: 3.7, text: "발표를 시작하겠습니다" },
];

// jsdom은 실제 브라우저와 달리 <summary> 클릭 시 <details>의 open을 자동으로
// 토글하지도, toggle 이벤트를 발생시키지도 않는다. CollapsibleDetails.test.jsx와
// 동일하게 실제 브라우저 동작(open 속성 변경 + toggle 이벤트 발생)을 직접 시뮬레이션한다.
function openSttSegmentDetails(container) {
    const details = container.querySelector("details");
    details.open = true;
    fireEvent(details, new Event("toggle"));
}

describe("SttSection", () => {
    it("renders segment start times as seek buttons and calls onSeekToTime with the segment start", () => {
        const onSeekToTime = vi.fn();

        const { container } = render(
            <SttSection
                sttInfo={baseSttInfo}
                audioExtractionInfo={{ success: true }}
                sttSegments={segments}
                onSeekToTime={onSeekToTime}
            />
        );

        openSttSegmentDetails(container);

        const seekButtons = screen.getAllByRole("button", { name: /영상을 .*초 지점으로 이동/ });
        expect(seekButtons).toHaveLength(2);

        fireEvent.click(seekButtons[1]);

        expect(onSeekToTime).toHaveBeenCalledWith(3.5);
    });

    it("renders segment start times as plain text (not buttons) without onSeekToTime", () => {
        const { container } = render(
            <SttSection
                sttInfo={baseSttInfo}
                audioExtractionInfo={{ success: true }}
                sttSegments={segments}
            />
        );

        openSttSegmentDetails(container);

        expect(
            screen.queryByRole("button", { name: /지점으로 이동/ })
        ).not.toBeInTheDocument();
        expect(screen.getByText("0초")).toBeInTheDocument();
    });

    it("shows guidance when there are no STT segments", () => {
        render(
            <SttSection
                sttInfo={baseSttInfo}
                audioExtractionInfo={{ success: true }}
                sttSegments={[]}
                onSeekToTime={vi.fn()}
            />
        );

        expect(screen.getByText("표시할 STT segment가 없습니다.")).toBeInTheDocument();
    });
});
