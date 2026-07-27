package com.hanium.presentation.global.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * backend가 예약하는 월간 NVIDIA 호출 수와 video-llm-engine의 실제 구간 분할 수가
 * 같은 설정으로 계산되는지 보장하기 위한 기본 범위 검증이다.
 */
@Component
public class VideoLlmBudgetStartupValidator {

    private final double chunkDurationSeconds;
    private final long maxVideoDurationMinutes;

    public VideoLlmBudgetStartupValidator(
            @Value("${video-llm.budget.chunk-duration-seconds:100}")
            double chunkDurationSeconds,
            @Value("${video.max-duration-minutes:30}")
            long maxVideoDurationMinutes
    ) {
        this.chunkDurationSeconds = chunkDurationSeconds;
        this.maxVideoDurationMinutes = maxVideoDurationMinutes;
    }

    @PostConstruct
    public void validate() {
        if (!Double.isFinite(chunkDurationSeconds) || chunkDurationSeconds <= 0) {
            throw new IllegalStateException(
                    "video-llm.budget.chunk-duration-seconds는 유한한 양수여야 합니다. value="
                            + chunkDurationSeconds
            );
        }
        if (maxVideoDurationMinutes < 1) {
            throw new IllegalStateException(
                    "video.max-duration-minutes는 1 이상이어야 합니다. value="
                            + maxVideoDurationMinutes
            );
        }

        double maximumCallUnits = Math.ceil(
                Math.multiplyExact(maxVideoDurationMinutes, 60L) / chunkDurationSeconds
        );
        if (maximumCallUnits > Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    "Video LLM 최대 세그먼트 수가 지원 범위를 초과합니다. value="
                            + maximumCallUnits
            );
        }
    }
}
