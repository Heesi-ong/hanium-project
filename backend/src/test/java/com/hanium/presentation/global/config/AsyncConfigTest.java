package com.hanium.presentation.global.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class AsyncConfigTest {

    private final AsyncConfig asyncConfig = new AsyncConfig();

    @Test
    void createsExecutorWithValidConfig() {
        ThreadPoolTaskExecutor executor = asyncConfig.analysisTaskExecutor(2, 4, 20, 25);

        assertThat(executor.getCorePoolSize()).isEqualTo(2);
        assertThat(executor.getMaxPoolSize()).isEqualTo(4);
    }

    @Test
    void rejectsCorePoolSizeBelowOne() {
        assertThatIllegalStateException()
                .isThrownBy(() -> asyncConfig.analysisTaskExecutor(0, 4, 20, 25))
                .withMessageContaining("core-pool-size");
    }

    @Test
    void rejectsMaxPoolSizeSmallerThanCorePoolSize() {
        assertThatIllegalStateException()
                .isThrownBy(() -> asyncConfig.analysisTaskExecutor(4, 2, 20, 25))
                .withMessageContaining("max-pool-size");
    }

    @Test
    void rejectsNegativeQueueCapacity() {
        assertThatIllegalStateException()
                .isThrownBy(() -> asyncConfig.analysisTaskExecutor(2, 4, -1, 25))
                .withMessageContaining("queue-capacity");
    }

    @Test
    void rejectsNegativeAwaitTerminationSeconds() {
        assertThatIllegalStateException()
                .isThrownBy(() -> asyncConfig.analysisTaskExecutor(2, 4, 20, -1))
                .withMessageContaining("await-termination-seconds");
    }

    @Test
    void allowsMaxPoolSizeEqualToCorePoolSize() {
        ThreadPoolTaskExecutor executor = asyncConfig.analysisTaskExecutor(3, 3, 20, 25);

        assertThat(executor.getCorePoolSize()).isEqualTo(3);
        assertThat(executor.getMaxPoolSize()).isEqualTo(3);
    }
}
