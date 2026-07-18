package com.hanium.presentation.infrastructure.video;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Component
public class FfprobeVideoDurationProbe implements VideoDurationProbe {

    private static final Logger log = LoggerFactory.getLogger(FfprobeVideoDurationProbe.class);
    private static final long FFPROBE_TIMEOUT_SECONDS = 10L;

    // ffprobe 실패 시 영상 길이 제한을 fail-open으로 통과시키는데, 이 상황이 조용히 늘어나면
    // 30분 제한이 사실상 무력화됩니다. 사유별로 카운터를 노출해 Prometheus에서 감시합니다.
    private final Counter successCounter;
    private final Counter failOpenTimeoutCounter;
    private final Counter failOpenExitNonZeroCounter;
    private final Counter failOpenErrorCounter;
    private final Counter failOpenInterruptedCounter;

    private volatile boolean warningLogged = false;

    public FfprobeVideoDurationProbe(MeterRegistry meterRegistry) {
        this.successCounter = Counter.builder("video_duration_probe.result")
                .description("ffprobe 영상 길이 확인 결과 건수")
                .tag("outcome", "success")
                .tag("reason", "none")
                .register(meterRegistry);
        this.failOpenTimeoutCounter = failOpenCounter(meterRegistry, "timeout");
        this.failOpenExitNonZeroCounter = failOpenCounter(meterRegistry, "exit_nonzero");
        this.failOpenErrorCounter = failOpenCounter(meterRegistry, "error");
        this.failOpenInterruptedCounter = failOpenCounter(meterRegistry, "interrupted");
    }

    private static Counter failOpenCounter(MeterRegistry meterRegistry, String reason) {
        return Counter.builder("video_duration_probe.result")
                .description("ffprobe 영상 길이 확인 결과 건수")
                .tag("outcome", "fail_open")
                .tag("reason", reason)
                .register(meterRegistry);
    }

    @Override
    public Optional<Duration> probe(Path videoPath) {
        Process process = null;

        try {
            process = new ProcessBuilder(
                    "ffprobe",
                    "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    videoPath.toString()
            )
                    .redirectErrorStream(true)
                    .start();

            if (!process.waitFor(FFPROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                failOpenTimeoutCounter.increment();
                logWarningOnce("ffprobe 재생 시간 확인이 타임아웃되어 영상 길이 제한을 fail-open으로 통과시킵니다. path={}", videoPath);
                return Optional.empty();
            }

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();

            if (process.exitValue() != 0) {
                failOpenExitNonZeroCounter.increment();
                logWarningOnce("ffprobe 재생 시간 확인이 실패해 영상 길이 제한을 fail-open으로 통과시킵니다. path={}", videoPath);
                return Optional.empty();
            }

            double seconds = Double.parseDouble(output);
            successCounter.increment();
            return Optional.of(Duration.ofMillis(Math.round(seconds * 1000)));
        } catch (IOException | NumberFormatException e) {
            failOpenErrorCounter.increment();
            logWarningOnce("ffprobe 재생 시간 확인 중 오류가 발생해 영상 길이 제한을 fail-open으로 통과시킵니다. path={}", videoPath);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failOpenInterruptedCounter.increment();
            logWarningOnce("ffprobe 재생 시간 확인이 인터럽트되어 영상 길이 제한을 fail-open으로 통과시킵니다. path={}", videoPath);
            return Optional.empty();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private void logWarningOnce(String message, Path videoPath) {
        if (!warningLogged) {
            warningLogged = true;
            log.warn(message, videoPath);
        }
    }
}
