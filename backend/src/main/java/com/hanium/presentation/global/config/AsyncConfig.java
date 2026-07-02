package com.hanium.presentation.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class AsyncConfig {

    // 영상 분석 파이프라인(analysis-engine / video-llm-engine / OpenAI 호출)을
    // HTTP 요청 스레드가 아니라 이 전용 스레드 풀에서 실행합니다.
    // 풀 크기가 "동시에 처리할 수 있는 분석 작업 수"의 자연스러운 상한이 되어,
    // 여러 명이 한꺼번에 업로드해도 서버 전체가 과부하로 멈추는 것을 막아줍니다.
    @Bean
    public ThreadPoolTaskExecutor analysisTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("analysis-worker-");
        // 풀과 대기열이 모두 가득 차면, 새 작업을 버리거나 실패시키는 대신 요청을 보낸
        // 스레드가 직접 실행하도록 해서(CallerRunsPolicy) 시스템이 완전히 죽지 않고
        // 서서히 느려지는 방식으로 대응합니다. 더 정교한 "대기열 가득 참" 안내는
        // 다음 단계 개선 과제로 남겨둡니다.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
