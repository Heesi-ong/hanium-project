package com.hanium.presentation.global.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * analysis.job.timeout-minutes(작업 1건이 실행 중 능동적으로 강제하는 최대 시간)는
 * analysis.stuck-job.max-running-minutes(재시작 등으로 멈춘 작업을 사후에 감지하는
 * 워치도그 임계값)보다 항상 작아야 한다. 그래야 워치도그가 개입하기 전에 job timeout이
 * 먼저 작동해 실패 처리한다. 이 관계는 AnalysisCommandService의 주석으로만 문서화돼
 * 있었고, 두 값이 서로 다른 클래스에서 각자 읽혀 실제로는 강제되지 않았다. 잘못
 * 설정하면(timeout >= watchdog 임계값) 정상 종료돼야 할 작업이 워치도그에만 의존하게
 * 되어 최대 30분 가까이 무의미하게 리소스를 붙잡을 수 있다(2026-07-23 코드 리뷰 P1-05).
 */
@Component
public class AnalysisTimeoutStartupValidator {

    private final long jobTimeoutMinutes;
    private final long stuckJobMaxRunningMinutes;

    public AnalysisTimeoutStartupValidator(
            @Value("${analysis.job.timeout-minutes:20}") long jobTimeoutMinutes,
            @Value("${analysis.stuck-job.max-running-minutes:30}") long stuckJobMaxRunningMinutes
    ) {
        this.jobTimeoutMinutes = jobTimeoutMinutes;
        this.stuckJobMaxRunningMinutes = stuckJobMaxRunningMinutes;
    }

    @PostConstruct
    public void validate() {
        if (jobTimeoutMinutes < 1) {
            throw new IllegalStateException(
                    "analysis.job.timeout-minutes(" + jobTimeoutMinutes + ")는 1 이상이어야 합니다."
            );
        }

        if (stuckJobMaxRunningMinutes < 1) {
            throw new IllegalStateException(
                    "analysis.stuck-job.max-running-minutes(" + stuckJobMaxRunningMinutes + ")는 1 이상이어야 합니다."
            );
        }

        if (jobTimeoutMinutes >= stuckJobMaxRunningMinutes) {
            throw new IllegalStateException(
                    "analysis.job.timeout-minutes(" + jobTimeoutMinutes + ")는 "
                            + "analysis.stuck-job.max-running-minutes(" + stuckJobMaxRunningMinutes + ")보다 "
                            + "작아야 합니다. 그래야 워치도그가 개입하기 전에 job timeout이 먼저 작동합니다."
            );
        }
    }
}
