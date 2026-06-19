// 백엔드 분석 단계 키를 사용자에게 보여줄 한국어 상태 문구로 매핑한다.
export const analysisStageLabels = {
  queued: "분석 대기 중",
  preparing: "분석 준비 중",
  video_info: "영상 정보 확인 중",
  extracting_frames: "프레임 추출 중",
  analyzing_pose: "자세 분석 중",
  analyzing_face: "표정·시선 분석 중",
  analyzing_timeline: "타임라인 분석 중",
  analyzing_audio: "음성 분석 중",
  calculating_scores: "점수와 피드백 계산 중",
  saving_result: "결과 저장 중",
  cancelling: "취소 처리 중",
  cancelled: "분석 취소됨",
  completed: "분석 완료",
  failed: "분석 실패",
};
