import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import AudioAnalysisSection from "./AudioAnalysisSection";

function renderMetricCard(label, value, description) {
    return (
        <article className="metric-card">
            <span>{label}</span>
            <strong>{value ?? 0}</strong>
            <p>{description}</p>
        </article>
    );
}

describe("AudioAnalysisSection", () => {
    it("renders volume stability metrics when analysis is implemented", () => {
        render(
            <AudioAnalysisSection
                renderMetricCard={renderMetricCard}
                audioInfo={{
                    speechScore: 84,
                    speechSpeedScore: 100,
                    silenceScore: 80,
                    volumeStabilityScore: 64,
                    speechSpeedWpm: 132,
                    estimatedWordCount: 120,
                    estimatedSpeechDurationSec: 54.2,
                    totalSilenceTime: 4.5,
                    silenceCount: 2,
                    silenceRatio: 0.08,
                    volumeStabilityImplemented: true,
                    volumeRmsDbStdDev: 8.25,
                    volumeAnalyzedWindowCount: 18,
                    volumeSilentWindowCount: 2,
                    analysisMethod: "stt_based_analysis",
                }}
            />
        );

        expect(screen.getByText("음량 안정성 점수")).toBeInTheDocument();
        expect(screen.getByText("64")).toBeInTheDocument();
        expect(screen.getByText("음량 변동성")).toBeInTheDocument();
        expect(screen.getByText("8.25 dB")).toBeInTheDocument();
        expect(screen.getByText("음량 분석 구간")).toBeInTheDocument();
        expect(screen.getByText("18개")).toBeInTheDocument();
        expect(screen.getByText("무음 구간")).toBeInTheDocument();
        expect(screen.getByText("2개")).toBeInTheDocument();
    });

    it("explains fallback reason when volume stability uses the neutral fallback", () => {
        render(
            <AudioAnalysisSection
                renderMetricCard={renderMetricCard}
                audioInfo={{
                    speechScore: 82,
                    speechSpeedScore: 100,
                    silenceScore: 80,
                    volumeStabilityScore: 80,
                    volumeStabilityImplemented: false,
                    volumeStabilityFallbackReason: "audio_unavailable",
                }}
            />
        );

        expect(screen.getByText("음량 변동성")).toBeInTheDocument();
        expect(screen.getByText(
            "음량 안정성은 오디오를 사용할 수 없음 사유로 중립값을 사용했습니다."
        )).toBeInTheDocument();
    });
});
