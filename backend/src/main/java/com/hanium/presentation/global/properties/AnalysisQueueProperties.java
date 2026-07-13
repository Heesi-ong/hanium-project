package com.hanium.presentation.global.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

// 워커가 느리거나 꺼진 상태에서도 QUEUED 작업이 DB에 무제한 쌓이지 않도록 하는 백프레셔 한도입니다.
// api/worker 분리 배포에서는 dispatch.local-on-run=false라 로컬 executor 포화 검사(rejectIfExecutorSaturated)가
// 동작하지 않으므로, 이 한도가 유일한 방어선입니다.
@ConfigurationProperties(prefix = "analysis.queue")
public record AnalysisQueueProperties(
        int maxGlobalQueued,
        int maxQueuedPerUser
) {
}
