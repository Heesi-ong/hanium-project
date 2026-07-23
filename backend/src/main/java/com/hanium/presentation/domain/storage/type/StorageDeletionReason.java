package com.hanium.presentation.domain.storage.type;

public enum StorageDeletionReason {
    // 결과 삭제 API/회원 탈퇴로 인한 삭제(ResultCommandService.deleteResult)
    RESULT_DELETE,
    // 원본 영상 보존 기간 만료로 인한 삭제(OriginalVideoRetentionService)
    ORIGINAL_VIDEO_RETENTION,
    // 로컬 디스크에서 발견된 고아(DB에 없는) 디렉터리 정리(StorageCleanupService)
    ORPHAN_CLEANUP
}
