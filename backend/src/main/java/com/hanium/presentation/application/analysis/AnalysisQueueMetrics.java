package com.hanium.presentation.application.analysis;

import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import com.hanium.presentation.global.properties.AnalysisQueueProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 분석 큐 상태를 게이지 메트릭으로 노출합니다. Prometheus가 스크레이프할 때마다 현재 값을 읽어
 * Grafana에서 "큐 적체(대기 작업 수)"와 "실행 중 작업 수"를 그래프로 볼 수 있게 합니다.
 *
 * <ul>
 *   <li>{@code analysis.queue.backlog} : 아직 실행되지 못하고 대기(QUEUED) 중인 작업 수.
 *       이 값이 계속 커지면 워커가 부족하거나 처리가 밀리고 있다는 신호입니다.</li>
 *   <li>{@code analysis.queue.running} : 현재 실행 중인 작업 수.</li>
 * </ul>
 *
 * <p>값은 DB를 조회해 계산하므로 어느 인스턴스에서 재든 동일합니다. 실행 담당 인스턴스
 * (monolith/worker)에서만 등록하며, worker를 여러 개로 확장하면 같은 값이 중복 보고되므로
 * 대시보드/알림에서는 {@code max()}로 집계합니다.</p>
 */
@Component
@ConditionalOnProperty(name = "analysis.worker.enabled", havingValue = "true", matchIfMissing = true)
public class AnalysisQueueMetrics {

    private static final List<AnalysisStatus> RUNNING_STATUSES = List.of(
            AnalysisStatus.BASIC_ANALYZING,
            AnalysisStatus.VIDEO_LLM_ANALYZING,
            AnalysisStatus.COMPACTING,
            AnalysisStatus.OPENAI_GENERATING,
            AnalysisStatus.MERGING_RESULT
    );

    public AnalysisQueueMetrics(
            AnalysisJobRepository analysisJobRepository,
            AnalysisQueueProperties analysisQueueProperties,
            MeterRegistry meterRegistry
    ) {
        Gauge.builder(
                        "analysis.queue.backlog",
                        analysisJobRepository,
                        repository -> repository.countByStatus(AnalysisStatus.QUEUED)
                )
                .description("실행 대기(QUEUED) 중인 분석 작업 수")
                .register(meterRegistry);

        Gauge.builder(
                        "analysis.queue.running",
                        analysisJobRepository,
                        repository -> repository.countByStatusIn(RUNNING_STATUSES)
                )
                .description("현재 실행 중인 분석 작업 수")
                .register(meterRegistry);

        // 설정된 백프레셔 한도를 게이지로 노출합니다. 알림 규칙이 절대값(예: ">20") 대신
        // backlog/limit 비율로 판단할 수 있게 해, 한도(analysis.queue.max-*)를 바꿔도
        // 알림 기준이 자동으로 따라오게 합니다.
        Gauge.builder(
                        "analysis.queue.limit",
                        analysisQueueProperties,
                        AnalysisQueueProperties::maxGlobalQueued
                )
                .tag("scope", "global")
                .description("설정된 QUEUED 백프레셔 한도")
                .register(meterRegistry);

        Gauge.builder(
                        "analysis.queue.limit",
                        analysisQueueProperties,
                        AnalysisQueueProperties::maxQueuedPerUser
                )
                .tag("scope", "per-user")
                .description("설정된 QUEUED 백프레셔 한도")
                .register(meterRegistry);
    }
}
