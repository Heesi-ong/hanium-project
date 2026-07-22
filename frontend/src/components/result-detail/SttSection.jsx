import {
    formatNumber,
    formatPercent,
    formatSttSuccess,
} from "./resultDetailFormatters";
import CollapsibleDetails from "../CollapsibleDetails";
import StateMessage from "../StateMessage";

function SttSection({
                        sttInfo,
                        audioExtractionInfo,
                        sttSegments,
                        onSeekToTime,
                    }) {
    return (
        <article className="detail-card wide">
            <h2>STT 변환 결과</h2>

            <div className="metric-grid">
                <article className="metric-card">
                    <span>STT 상태</span>
                    <strong>{formatSttSuccess(sttInfo?.success)}</strong>
                    <p>음성 파일을 텍스트로 변환했는지 여부입니다.</p>
                </article>

                <article className="metric-card">
                    <span>STT 모델</span>
                    <strong>{sttInfo?.modelSize || "-"}</strong>
                    <p>faster-whisper 모델 크기입니다.</p>
                </article>

                <article className="metric-card">
                    <span>언어</span>
                    <strong>{sttInfo?.language || "-"}</strong>
                    <p>Whisper가 감지한 음성 언어입니다.</p>
                </article>

                <article className="metric-card">
                    <span>언어 확률</span>
                    <strong>{formatPercent(sttInfo?.languageProbability)}</strong>
                    <p>감지 언어에 대한 모델의 추정 확률입니다.</p>
                </article>

                <article className="metric-card">
                    <span>Segment 수</span>
                    <strong>{sttInfo?.segmentCount ?? 0}개</strong>
                    <p>STT가 나눈 발화 구간 수입니다.</p>
                </article>

                <article className="metric-card">
                    <span>오디오 추출</span>
                    <strong>{audioExtractionInfo?.success ? "성공" : "실패"}</strong>
                    <p>영상에서 wav 오디오를 분리했는지 여부입니다.</p>
                </article>
            </div>

            {audioExtractionInfo?.audioPath && (
                <div className="key-value-list">
                    <div className="key-value-item">
                        <span>audio.wav 저장 경로</span>
                        <strong>{audioExtractionInfo.audioPath}</strong>
                    </div>
                </div>
            )}

            {sttInfo?.error && (
                <StateMessage type="error">{sttInfo.error}</StateMessage>
            )}

            <div className="feedback-block">
                <h3>Transcript</h3>
                <p>{sttInfo?.transcript || "표시할 STT 변환 텍스트가 없습니다."}</p>
            </div>

            {Array.isArray(sttSegments) && sttSegments.length > 0 ? (
                <CollapsibleDetails
                    headingLevel={3}
                    summary={`STT Segment (${sttSegments.length}개 구간) — 자세히 보기`}
                >
                    <div className="pose-frame-table-wrap">
                        <table className="pose-frame-table">
                            <thead>
                            <tr>
                                <th>순서</th>
                                <th>시작</th>
                                <th>끝</th>
                                <th>길이</th>
                                <th>텍스트</th>
                            </tr>
                            </thead>

                            <tbody>
                            {sttSegments.map((segment, index) => (
                                <tr key={`${segment.start}-${segment.end}-${index}`}>
                                    <td>{index + 1}</td>
                                    <td>
                                        {onSeekToTime && typeof segment.start === "number" ? (
                                            <button
                                                type="button"
                                                className="observation-time observation-seek"
                                                onClick={() => onSeekToTime(segment.start)}
                                                aria-label={`영상을 ${formatNumber(segment.start)}초 지점으로 이동`}
                                            >
                                                {formatNumber(segment.start)}초
                                            </button>
                                        ) : (
                                            `${formatNumber(segment.start)}초`
                                        )}
                                    </td>
                                    <td>{formatNumber(segment.end)}초</td>
                                    <td>{formatNumber(segment.duration)}초</td>
                                    <td>{segment.text || "-"}</td>
                                </tr>
                            ))}
                            </tbody>
                        </table>
                    </div>
                </CollapsibleDetails>
            ) : (
                <p className="muted-text">표시할 STT segment가 없습니다.</p>
            )}
        </article>
    );
}

export default SttSection;