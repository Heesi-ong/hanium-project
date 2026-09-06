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
    const sttStatus = sttInfo?.success === true
        ? { label: "텍스트 변환 성공", className: "success" }
        : sttInfo?.success === false
            ? { label: "텍스트 변환 실패", className: "warning" }
            : { label: "변환 상태 미확인", className: "muted" };
    const audioExtractionStatus = audioExtractionInfo?.success === true
        ? "성공"
        : audioExtractionInfo?.success === false
            ? "실패"
            : "-";

    return (
        <article className="detail-card wide result-speech-card result-stt-card">
            <header className="result-speech-card-header">
                <div>
                    <span className="result-speech-kicker">Speech to text</span>
                    <h2>STT 변환 결과</h2>
                    <p>발표 원문을 먼저 읽고, 필요한 구간은 시작 시간을 눌러 영상에서 확인할 수 있습니다.</p>
                </div>
                <span className={`mini-badge ${sttStatus.className}`}>
                    {sttStatus.label}
                </span>
            </header>

            <dl className="result-stt-facts" aria-label="STT 변환 정보">
                <div>
                    <dt>STT 상태</dt>
                    <dd>{formatSttSuccess(sttInfo?.success)}</dd>
                </div>
                <div>
                    <dt>STT 모델</dt>
                    <dd>{sttInfo?.modelSize || "-"}</dd>
                </div>
                <div>
                    <dt>감지 언어</dt>
                    <dd>{sttInfo?.language || "-"}</dd>
                </div>
                <div>
                    <dt>언어 확률</dt>
                    <dd>{formatPercent(sttInfo?.languageProbability)}</dd>
                </div>
                <div>
                    <dt>발화 구간</dt>
                    <dd>{sttInfo?.segmentCount ?? 0}개</dd>
                </div>
                <div>
                    <dt>오디오 추출</dt>
                    <dd>{audioExtractionStatus}</dd>
                </div>
            </dl>

            {audioExtractionInfo?.audioPath && (
                <div className="result-speech-technical-path">
                    <span>audio.wav 저장 경로</span>
                    <code>{audioExtractionInfo.audioPath}</code>
                </div>
            )}

            {sttInfo?.error && (
                <StateMessage type="error">{sttInfo.error}</StateMessage>
            )}

            <section className="result-transcript-block" aria-labelledby="stt-transcript-heading">
                <div className="result-transcript-heading">
                    <div>
                        <span aria-hidden="true">T</span>
                        <h3 id="stt-transcript-heading">Transcript</h3>
                    </div>
                    <span>{sttInfo?.segmentCount ?? 0}개 구간</span>
                </div>
                <p>{sttInfo?.transcript || "표시할 STT 변환 텍스트가 없습니다."}</p>
            </section>

            {Array.isArray(sttSegments) && sttSegments.length > 0 ? (
                <CollapsibleDetails
                    headingLevel={3}
                    className="result-speech-details"
                    summary={`STT Segment (${sttSegments.length}개 구간) — 자세히 보기`}
                >
                    <div className="pose-frame-table-wrap result-speech-table-wrap">
                        <table className="pose-frame-table">
                            <caption className="sr-only">STT 발화 구간별 시작 및 종료 시간</caption>
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
                <div className="result-speech-empty compact">
                    <span aria-hidden="true">—</span>
                    <div>
                        <strong>표시할 STT segment가 없습니다.</strong>
                        <p>구간 정보가 없는 결과에서도 전체 Transcript는 위에서 확인할 수 있습니다.</p>
                    </div>
                </div>
            )}
        </article>
    );
}

export default SttSection;
