export function formatNumber(value, digits = 2) {
    if (value === null || value === undefined || value === "") {
        return "-";
    }

    if (typeof value !== "number") {
        return value;
    }

    return Number.isInteger(value) ? value : value.toFixed(digits);
}

export function formatPercent(value) {
    if (value === null || value === undefined || typeof value !== "number") {
        return "-";
    }

    return `${Math.round(value * 100)}%`;
}

export function formatFileSize(fileSize) {
    if (!fileSize && fileSize !== 0) {
        return "-";
    }

    return `${(fileSize / 1024 / 1024).toFixed(2)}MB`;
}

export function formatBoolean(value) {
    if (value === true) {
        return "예";
    }

    if (value === false) {
        return "아니오";
    }

    return "-";
}

export function formatEmotionLabel(label) {
    if (label === "neutral") {
        return "중립";
    }

    if (label === "engaged") {
        return "몰입/집중";
    }

    if (label === "speaking") {
        return "발화 중";
    }

    if (label === "low_energy") {
        return "낮은 에너지";
    }

    if (label === "unknown") {
        return "알 수 없음";
    }

    return label || "-";
}

export function formatEyeContactLevel(level) {
    if (level === "good") {
        return "좋음";
    }

    if (level === "normal") {
        return "보통";
    }

    if (level === "weak") {
        return "약함";
    }

    if (level === "poor") {
        return "부족";
    }

    return "알 수 없음";
}

export function formatGazeDirection(direction) {
    if (direction === "left") {
        return "왼쪽";
    }

    if (direction === "right") {
        return "오른쪽";
    }

    if (direction === "center") {
        return "중앙";
    }

    return "알 수 없음";
}

export function formatSttSuccess(success) {
    if (success === true) {
        return "성공";
    }

    if (success === false) {
        return "실패";
    }

    return "-";
}

export function formatAnalysisMethod(method) {
    if (method === "duration_based_estimation") {
        return "영상 길이 기반 추정";
    }

    if (method === "audio_extracted_duration_based_estimation") {
        return "오디오 추출 + 길이 기반 추정";
    }

    if (method === "stt_based_analysis") {
        return "STT 기반 분석";
    }

    if (method === "stt_based_filler_detection") {
        return "STT 기반 필러 탐지";
    }

    if (method === "faster_whisper") {
        return "faster-whisper";
    }

    if (method === "mediapipe_pose_wrist_elbow_based") {
        return "MediaPipe 팔/손목 기반";
    }

    if (method === "mediapipe_face_mesh_expression_based") {
        return "MediaPipe Face Mesh 표정 기반";
    }

    if (method === "mediapipe_tasks_pose_landmarker") {
        return "MediaPipe Tasks PoseLandmarker";
    }

    if (method === "mediapipe_tasks_face_landmarker") {
        return "MediaPipe Tasks FaceLandmarker";
    }

    if (method === "mediapipe_tasks_pose_landmarker_wrist_elbow_based") {
        return "MediaPipe Tasks 팔/손목 기반";
    }

    if (method === "mediapipe_tasks_face_landmarker_expression_based") {
        return "MediaPipe Tasks 표정 기반";
    }

    if (!method) {
        return "-";
    }

    return method;
}