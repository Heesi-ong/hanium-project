package com.hanium.presentation.presentation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 관리자 파괴적 조치(정지, 강제 탈퇴, 결과 삭제, 수동 재큐잉)에서 공통으로 쓰는 요청
// 본문입니다. reason은 관리자가 왜 이 조치를 실행하는지 남기는 필수 입력이고,
// incidentId는 외부 인시던트/문의 티켓 번호를 선택적으로 연결하기 위한 필드입니다
// (2026-08-03 서비스화 점검 P2-03).
public record AdminActionReasonRequest(
        @NotBlank(message = "사유를 입력해주세요.")
        @Size(max = 500, message = "사유는 500자 이하로 입력해주세요.")
        String reason,

        @Size(max = 100, message = "인시던트/참조 ID는 100자 이하로 입력해주세요.")
        String incidentId
) {
    public AdminActionReasonRequest {
        reason = reason == null ? null : reason.trim();
        incidentId = incidentId == null || incidentId.isBlank()
                ? null
                : incidentId.trim();
    }
}
