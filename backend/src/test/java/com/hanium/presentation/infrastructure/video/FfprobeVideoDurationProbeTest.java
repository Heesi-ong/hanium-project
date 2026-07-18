package com.hanium.presentation.infrastructure.video;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FfprobeVideoDurationProbeTest {

    @Test
    void registersCountersWithConsistentPrometheusTagKeys() {
        PrometheusMeterRegistry meterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        new FfprobeVideoDurationProbe(meterRegistry);

        String scrape = meterRegistry.scrape();

        assertThat(scrape).contains("video_duration_probe_result_total{");
        assertThat(scrape).contains("outcome=\"success\"");
        assertThat(scrape).contains("reason=\"none\"");
        assertThat(scrape).contains("outcome=\"fail_open\"");
        assertThat(scrape).contains("reason=\"timeout\"");
    }

    @Test
    void probeReturnsEmptyWhenFfprobeCannotReadVideo() throws Exception {
        assumeTrue(isFfprobeAvailable(), "ffprobe not available");

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        FfprobeVideoDurationProbe probe = new FfprobeVideoDurationProbe(meterRegistry);

        assertThat(probe.probe(Path.of("missing-video-file.mp4"))).isEqualTo(Optional.empty());

        // ffprobe가 존재하지 않는 파일을 읽지 못하면 fail-open 카운터가 하나 증가해야 합니다.
        double failOpenCount = meterRegistry.find("video_duration_probe.result")
                .tag("outcome", "fail_open")
                .counters()
                .stream()
                .mapToDouble(counter -> counter.count())
                .sum();
        assertThat(failOpenCount).isEqualTo(1.0);
    }

    private boolean isFfprobeAvailable() throws Exception {
        try {
            Process process = new ProcessBuilder("ffprobe", "-version").start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
