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

function AudioDetailMetric({ label, value, description }) {
    return (
        <div className="result-speech-detail-item">
            <dt>{label}</dt>
            <dd>
                <strong>{value}</strong>
                <span>{description}</span>
            </dd>
        </div>
    );
}

function AudioAnalysisSection({ audioInfo, renderMetricCard }) {
    const volumeStatus = audioInfo?.volumeStabilityImplemented === true
        ? { label: "음량 실측값 포함", className: "success" }
        : audioInfo?.volumeStabilityImplemented === false
            ? { label: "음량 중립값 포함", className: "warning" }
            : { label: "세부 데이터 확인", className: "muted" };

    return (
        <article className="detail-card wide result-speech-card result-audio-analysis-card">
            <header className="result-speech-card-header">
                <div>
                    <span className="result-speech-kicker">Voice delivery</span>
                    <h2>음성 분석 요약</h2>
                    <p>말하기 속도와 침묵, 음량 안정성을 핵심 점수와 측정값으로 나눠 보여줍니다.</p>
                </div>
                <span className={`mini-badge ${volumeStatus.className}`}>
                    {volumeStatus.label}
                </span>
            </header>

            <section className="result-speech-score-section" aria-labelledby="audio-score-heading">
                <div className="result-speech-section-heading">
                    <div>
                        <span aria-hidden="true">01</span>
                        <h3 id="audio-score-heading">핵심 음성 점수</h3>
                    </div>
                    <p>점수가 높을수록 목표 범위에 가깝습니다.</p>
                </div>

                <div className="result-speech-score-grid">
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
                </div>
            </section>

            <section className="result-speech-detail-section" aria-labelledby="audio-detail-heading">
                <div className="result-speech-section-heading">
                    <div>
                        <span aria-hidden="true">02</span>
                        <h3 id="audio-detail-heading">측정값 자세히 보기</h3>
                    </div>
                    <p>백엔드가 제공한 실제 분석 수치입니다.</p>
                </div>

                <dl className="result-speech-detail-grid">
                    <AudioDetailMetric
                        label="WPM"
                        value={audioInfo?.speechSpeedWpm ?? 0}
                        description="분당 단어 수"
                    />
                    <AudioDetailMetric
                        label="단어 수"
                        value={`${audioInfo?.estimatedWordCount ?? 0}개`}
                        description="STT transcript 기준"
                    />
                    <AudioDetailMetric
                        label="발화 시간"
                        value={`${formatNumber(audioInfo?.estimatedSpeechDurationSec)}초`}
                        description="STT segment 합계"
                    />
                    <AudioDetailMetric
                        label="침묵 시간"
                        value={`${formatNumber(audioInfo?.totalSilenceTime)}초`}
                        description="발화 구간 사이 공백"
                    />
                    <AudioDetailMetric
                        label="침묵 횟수"
                        value={`${audioInfo?.silenceCount ?? 0}회`}
                        description="1초 이상 공백"
                    />
                    <AudioDetailMetric
                        label="침묵 비율"
                        value={formatPercent(audioInfo?.silenceRatio)}
                        description="전체 발표 시간 대비"
                    />
                    <AudioDetailMetric
                        label="음량 변동성"
                        value={audioInfo?.volumeStabilityImplemented
                            ? `${formatNumber(audioInfo?.volumeRmsDbStdDev)} dB`
                            : "-"}
                        description="RMS dB 표준편차"
                    />
                    <AudioDetailMetric
                        label="음량 분석 구간"
                        value={`${audioInfo?.volumeAnalyzedWindowCount ?? 0}개`}
                        description="0.5초 단위 구간"
                    />
                    <AudioDetailMetric
                        label="무음 구간"
                        value={`${audioInfo?.volumeSilentWindowCount ?? 0}개`}
                        description="안정성 계산에서 제외"
                    />
                    <AudioDetailMetric
                        label="분석 방식"
                        value={formatAnalysisMethod(audioInfo?.analysisMethod)}
                        description="현재 적용된 계산 방식"
                    />
                </dl>
            </section>

            {audioInfo?.note && (
                <div className="result-speech-notice">
                    <span aria-hidden="true">i</span>
                    <p>{audioInfo.note}</p>
                </div>
            )}
            {audioInfo?.volumeStabilityImplemented === false &&
                audioInfo?.volumeStabilityFallbackReason && (
                    <div className="result-speech-notice warning" role="note">
                        <span aria-hidden="true">!</span>
                        <div>
                            <strong>음량 안정성 중립값 적용</strong>
                            <p>
                                음량 안정성은 {formatVolumeFallbackReason(audioInfo.volumeStabilityFallbackReason)} 사유로 중립값을 사용했습니다.
                            </p>
                        </div>
                    </div>
                )}
        </article>
    );
}

export default AudioAnalysisSection;
