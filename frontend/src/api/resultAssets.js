import { API_BASE_URL } from "./apiClient";

/**
 * 분석 결과에 딸린 오버레이 프레임 이미지의 URL을 만듭니다.
 *
 * 이 URL은 <img> 태그의 src로 바로 쓰입니다. 인증은 JWT 쿠키로 처리되고(브라우저가
 * 이미지 요청에도 쿠키를 함께 보냄), 서버에서 결과 소유자만 접근할 수 있도록 검사합니다.
 */
export function buildOverlayFrameUrl(jobId, fileName) {
    return `${API_BASE_URL}/api/results/${encodeURIComponent(jobId)}/frames/${encodeURIComponent(fileName)}`;
}
