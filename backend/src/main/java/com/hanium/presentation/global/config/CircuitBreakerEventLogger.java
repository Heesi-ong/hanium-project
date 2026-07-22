package com.hanium.presentation.global.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class CircuitBreakerEventLogger {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerEventLogger.class);

    public CircuitBreakerEventLogger(CircuitBreakerRegistry registry) {
        register(registry.circuitBreaker("analysis-engine"));
        register(registry.circuitBreaker("video-llm-engine"));
    }

    private void register(CircuitBreaker circuitBreaker) {
        circuitBreaker.getEventPublisher()
                .onStateTransition(event -> log.warn(
                        "engine circuit breaker state transition: name={} from={} to={} failureRate={} slowCallRate={}",
                        circuitBreaker.getName(),
                        event.getStateTransition().getFromState(),
                        event.getStateTransition().getToState(),
                        circuitBreaker.getMetrics().getFailureRate(),
                        circuitBreaker.getMetrics().getSlowCallRate()
                ))
                .onCallNotPermitted(event -> log.warn(
                        "engine circuit breaker rejected call: name={} state={}",
                        circuitBreaker.getName(),
                        circuitBreaker.getState()
                ));
    }
}
