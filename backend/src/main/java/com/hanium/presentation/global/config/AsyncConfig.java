package com.hanium.presentation.global.config;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class AsyncConfig {

    // 영상 분석 파이프라인(analysis-engine / video-llm-engine / OpenAI 호출)을
    // HTTP 요청 스레드가 아니라 이 전용 스레드 풀에서 실행합니다.
    // 풀 크기가 "동시에 처리할 수 있는 분석 작업 수"의 자연스러운 상한이 되어,
    // 여러 명이 한꺼번에 업로드해도 서버 전체가 과부하로 멈추는 것을 막아줍니다.
    // 컨테이너 재시작 등으로 종료 신호를 받았을 때 진행 중이던 분석 작업이 즉시 끊기지 않고
    // 짧게라도 정상 종료될 기회를 줍니다. 그래도 제한 시간 안에 끝나지 못한 작업은
    // StuckAnalysisJobWatchdogService가 이후 멈춘 작업으로 감지해 복구합니다.
    @Bean
    public ThreadPoolTaskExecutor analysisTaskExecutor(
            @Value("${analysis.executor.core-pool-size:2}") int corePoolSize,
            @Value("${analysis.executor.max-pool-size:4}") int maxPoolSize,
            @Value("${analysis.executor.queue-capacity:20}") int queueCapacity,
            @Value("${analysis.executor.await-termination-seconds:25}") int analysisExecutorAwaitTerminationSeconds
    ) {
        validateExecutorConfig(corePoolSize, maxPoolSize, queueCapacity, analysisExecutorAwaitTerminationSeconds);

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("analysis-worker-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(analysisExecutorAwaitTerminationSeconds);
        // 풀과 대기열이 모두 가득 차면 새 작업을 받지 않고 RejectedExecutionException을
        // 던집니다(AbortPolicy). 예전에는 CallerRunsPolicy로 "요청을 보낸 스레드가 직접
        // 실행"했는데, 그러면 사람이 몰리는 바로 그 순간에 HTTP 요청 스레드가 무거운
        // 영상 분석을 끝까지 처리하게 되어(=우리가 피하려던 동기 처리) 다른 API 응답까지
        // 함께 느려졌습니다. 이제는 대기열이 가득 차면 AnalysisCommandService가 이를
        // 감지해 사용자에게 429(잠시 후 재시도)로 명확히 안내합니다.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // MDC(requestId 등)는 스레드 로컬이라 워커 스레드로 자동 전파되지 않습니다.
        // 작업을 제출한 스레드의 MDC 컨텍스트를 캡처해 워커 스레드에서 복원하고,
        // 작업이 끝나면 반드시 정리해 스레드 재사용 시 값이 섞이지 않게 합니다.
        executor.setTaskDecorator(runnable -> {
            Map<String, String> contextMap = MDC.getCopyOfContextMap();
            return () -> {
                if (contextMap != null) {
                    MDC.setContextMap(contextMap);
                }
                try {
                    runnable.run();
                } finally {
                    MDC.clear();
                }
            };
        });
        executor.initialize();
        return executor;
    }

    // core > max처럼 잘못된 조합은 실제로는 ThreadPoolExecutor 생성자가 IllegalArgumentException을
    // 던지긴 하지만, Spring 빈 초기화 스택 트레이스에 묻혀 원인(어떤 설정값 조합이 문제인지)을
    // 바로 알아보기 어렵다. 값 자체와 관계를 먼저 이름 그대로 검증해 원인을 바로 드러낸다
    // (2026-07-23 코드 리뷰 P1-05).
    private void validateExecutorConfig(
            int corePoolSize,
            int maxPoolSize,
            int queueCapacity,
            int awaitTerminationSeconds
    ) {
        if (corePoolSize < 1) {
            throw new IllegalStateException(
                    "analysis.executor.core-pool-size must be at least 1, got " + corePoolSize
            );
        }

        if (maxPoolSize < corePoolSize) {
            throw new IllegalStateException(
                    "analysis.executor.max-pool-size(" + maxPoolSize + ") must be >= "
                            + "analysis.executor.core-pool-size(" + corePoolSize + ")"
            );
        }

        if (queueCapacity < 0) {
            throw new IllegalStateException(
                    "analysis.executor.queue-capacity must not be negative, got " + queueCapacity
            );
        }

        if (awaitTerminationSeconds < 0) {
            throw new IllegalStateException(
                    "analysis.executor.await-termination-seconds must not be negative, got "
                            + awaitTerminationSeconds
            );
        }
    }
}
