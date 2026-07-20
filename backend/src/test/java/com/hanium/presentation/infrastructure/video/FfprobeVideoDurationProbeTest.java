package com.hanium.presentation.infrastructure.video;

import io.micrometer.core.instrument.Counter;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
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

    // 기존 테스트는 "존재하지 않는 파일" fail-open 분기만 다뤘고, 정작 이 클래스의
    // 핵심 목적인 "실제 영상에서 길이를 정확히 읽어오는지"는 검증되지 않고 있었다.
    // 저장소에 미리 영상 자산을 커밋하는 대신, ffmpeg로 길이가 정확히 알려진 짧은
    // 영상을 테스트 시점에 직접 만들어 사용한다.
    @Test
    void probeReturnsDurationForRealVideoFile(@TempDir Path tempDir) throws Exception {
        assumeTrue(isFfprobeAvailable(), "ffprobe not available");
        assumeTrue(isFfmpegAvailable(), "ffmpeg not available");

        Path videoPath = tempDir.resolve("probe-sample.mp4");
        createRealVideoFile(videoPath, 2.0);

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        FfprobeVideoDurationProbe probe = new FfprobeVideoDurationProbe(meterRegistry);

        Optional<Duration> duration = probe.probe(videoPath);

        assertThat(duration).isPresent();
        // 인코딩 오차를 감안해 대략 2초 근처인지만 확인합니다.
        assertThat(duration.get().toMillis()).isBetween(1800L, 2200L);

        double successCount = meterRegistry.find("video_duration_probe.result")
                .tag("outcome", "success")
                .counters()
                .stream()
                .mapToDouble(Counter::count)
                .sum();
        assertThat(successCount).isEqualTo(1.0);
    }

    private void createRealVideoFile(Path videoPath, double durationSeconds) throws Exception {
        Process process = new ProcessBuilder(
                "ffmpeg", "-y",
                "-f", "lavfi", "-i", "color=c=blue:s=64x64:d=" + durationSeconds,
                "-c:v", "libx264",
                videoPath.toString()
        )
                .redirectErrorStream(true)
                .start();

        boolean finished = process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished || process.exitValue() != 0) {
            process.destroyForcibly();
            throw new IllegalStateException("테스트용 샘플 영상 생성(ffmpeg)에 실패했습니다.");
        }
    }

    private boolean isFfprobeAvailable() throws Exception {
        try {
            Process process = new ProcessBuilder("ffprobe", "-version").start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isFfmpegAvailable() throws Exception {
        try {
            Process process = new ProcessBuilder("ffmpeg", "-version").start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
