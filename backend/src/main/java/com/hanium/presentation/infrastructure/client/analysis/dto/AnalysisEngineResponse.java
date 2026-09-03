package com.hanium.presentation.infrastructure.client.analysis.dto;

import java.util.List;
import java.util.Map;

public record AnalysisEngineResponse(
        String jobId,
        String status,
        // 분석 엔진이 각 단계에서 무엇을 했는지 순서대로 기록한 처리 로그입니다.
        List<Map<String, Object>> analysisTrace,
        // 프레임 위에 포즈/제스처를 그린 base64 JPEG 목록입니다. 파이프라인이 스토리지에
        // 저장한 뒤 원본 응답에서는 비우고, 대신 frameGallery에 경로 메타데이터만 남깁니다.
        List<Map<String, Object>> frameOverlays,
        // 저장된 오버레이 프레임의 메타데이터(sequence/timestampSec/poseDetected/fileName)입니다.
        List<Map<String, Object>> frameGallery,
        Map<String, Object> videoInfo,
        Map<String, Object> frame,
        Map<String, Object> audio,
        Map<String, Object> filler,
        Map<String, Object> pose,
        Map<String, Object> gesture,
        Map<String, Object> face,
        Map<String, Object> emotion,
        Map<String, Object> score,
        Map<String, Object> error
) {

    public AnalysisEngineResponse {
        analysisTrace = analysisTrace == null ? List.of() : analysisTrace;
        frameOverlays = frameOverlays == null ? List.of() : frameOverlays;
        frameGallery = frameGallery == null ? List.of() : frameGallery;
    }

    /**
     * 트레이스/오버레이가 없던 시절의 호출부(주로 테스트 픽스처) 호환용 생성자입니다.
     * 분석 엔진 응답 역직렬화는 위 canonical 생성자를 사용합니다.
     */
    public AnalysisEngineResponse(
            String jobId,
            String status,
            Map<String, Object> videoInfo,
            Map<String, Object> frame,
            Map<String, Object> audio,
            Map<String, Object> filler,
            Map<String, Object> pose,
            Map<String, Object> gesture,
            Map<String, Object> face,
            Map<String, Object> emotion,
            Map<String, Object> score,
            Map<String, Object> error
    ) {
        this(
                jobId,
                status,
                List.of(),
                List.of(),
                List.of(),
                videoInfo,
                frame,
                audio,
                filler,
                pose,
                gesture,
                face,
                emotion,
                score,
                error
        );
    }

    /**
     * 프레임 오버레이를 스토리지에 저장한 뒤, base64 원본은 버리고 갤러리 메타데이터만
     * 남긴 사본을 반환합니다. 이후 파이프라인(compact/merge/raw 저장)은 큰 base64를
     * 메모리에 들고 다니지 않습니다.
     */
    public AnalysisEngineResponse withPersistedFrameGallery(List<Map<String, Object>> gallery) {
        return new AnalysisEngineResponse(
                jobId,
                status,
                analysisTrace,
                List.of(),
                gallery == null ? List.of() : gallery,
                videoInfo,
                frame,
                audio,
                filler,
                pose,
                gesture,
                face,
                emotion,
                score,
                error
        );
    }
}
