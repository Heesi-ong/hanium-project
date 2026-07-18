import {
    formatAnalysisMethod,
    formatNumber,
    formatPercent,
} from "./resultDetailFormatters";

function formatVolumeFallbackReason(reason) {
    if (reason === "audio_unavailable") {
        return "오디오를 사용할 수 없음";
    }

    if (reason === "audio_file_missing") {
        return "오디오 파일 없음";
    }

    if (reason === "unsupported_sample_width") {
        return "지원하지 않는 오디오 형식";
    }

    if (reason === "insufficient_non_silent_audio") {
        return "분석 가능한 발화 구간 부족";
    }

    if (reason === "analysis_failed") {
        return "분석 실패";
    }

    return reason || "알 수 없음";
}

function AudioAnalysisSection({ audioInfo, renderMetricCard }) {
    return (
        <article className="detail-card wide">
            <h2>음성 분석 요약</h2>

            <div className="metric-grid">
                {renderMetricCard(
                    "음성 점수",
                    audioInfo?.speechScore,
                    "말하기 속도, 침묵, 필러 표현, 음량 안정성을 합산한 음성 평가 점수입니다."
                )}

                {renderMetricCard(
                    "말하기 속도 점수",
                    audioInfo?.speechSpeedScore,
                    "WPM이 적정 범위에 가까울수록 높은 점수입니다."
                )}

                {renderMetricCard(
                    "침묵 점수",
                    audioInfo?.silenceScore,
                    "전체 길이 대비 침묵 비율이 낮을수록 높은 점수입니다."
                )}

                {renderMetricCard(
                    "음량 안정성 점수",
                    audioInfo?.volumeStabilityScore,
                    "비침묵 음성 구간의 RMS dB 변동이 작을수록 높은 점수입니다."
                )}

                <article className="metric-card">
                    <span>WPM</span>
                    <strong>{audioInfo?.speechSpeedWpm ?? 0}</strong>
                    <p>STT 발화 구간과 단어 수를 기준으로 계산한 분당 단어 수입니다.</p>
                </article>

                <article className="metric-card">
                    <span>단어 수</span>
                    <strong>{audioInfo?.estimatedWordCount ?? 0}개</strong>
                    <p>STT transcript 기준 단어 수입니다.</p>
                </article>

                <article className="metric-card">
                    <span>발화 시간</span>
                    <strong>{formatNumber(audioInfo?.estimatedSpeechDurationSec)}초</strong>
                    <p>STT segment 기준 실제 발화 시간 합계입니다.</p>
                </article>

                <article className="metric-card">
                    <span>침묵 시간</span>
                    <strong>{formatNumber(audioInfo?.totalSilenceTime)}초</strong>
                    <p>STT segment 사이 공백으로 계산한 침묵 시간입니다.</p>
                </article>

                <article className="metric-card">
                    <span>침묵 횟수</span>
                    <strong>{audioInfo?.silenceCount ?? 0}회</strong>
                    <p>1초 이상 발화 공백이 발생한 횟수입니다.</p>
                </article>

                <article className="metric-card">
                    <span>침묵 비율</span>
                    <strong>{formatPercent(audioInfo?.silenceRatio)}</strong>
                    <p>전체 발표 시간 대비 침묵 시간의 비율입니다.</p>
                </article>

                <article className="metric-card">
                    <span>음량 변동성</span>
                    <strong>
                        {audioInfo?.volumeStabilityImplemented
                            ? `${formatNumber(audioInfo?.volumeRmsDbStdDev)} dB`
                            : "-"}
                    </strong>
                    <p>비침묵 음성 윈도우의 RMS dB 표준편차입니다.</p>
                </article>

                <article className="metric-card">
                    <span>음량 분석 구간</span>
                    <strong>{audioInfo?.volumeAnalyzedWindowCount ?? 0}개</strong>
                    <p>0.5초 단위로 나눈 오디오 분석 구간 수입니다.</p>
                </article>

                <article className="metric-card">
                    <span>무음 구간</span>
                    <strong>{audioInfo?.volumeSilentWindowCount ?? 0}개</strong>
                    <p>음량 안정성 계산에서 제외한 무음성 구간 수입니다.</p>
                </article>

                <article className="metric-card">
                    <span>분석 방식</span>
                    <strong>{formatAnalysisMethod(audioInfo?.analysisMethod)}</strong>
                    <p>현재 음성 분석에 사용된 계산 방식입니다.</p>
                </article>
            </div>

            {audioInfo?.note && <p className="muted-text">{audioInfo.note}</p>}
            {audioInfo?.volumeStabilityImplemented === false &&
                audioInfo?.volumeStabilityFallbackReason && (
                    <p className="muted-text">
                        음량 안정성은 {formatVolumeFallbackReason(audioInfo.volumeStabilityFallbackReason)} 사유로 중립값을 사용했습니다.
                    </p>
                )}
        </article>
    );
}

export default AudioAnalysisSection;
