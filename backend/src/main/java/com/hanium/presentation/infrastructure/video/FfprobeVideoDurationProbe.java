package com.hanium.presentation.infrastructure.video;

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

    private volatile boolean warningLogged = false;

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
                logWarningOnce("ffprobe 재생 시간 확인이 타임아웃되어 영상 길이 제한을 fail-open으로 통과시킵니다. path={}", videoPath);
                return Optional.empty();
            }

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();

            if (process.exitValue() != 0) {
                logWarningOnce("ffprobe 재생 시간 확인이 실패해 영상 길이 제한을 fail-open으로 통과시킵니다. path={}", videoPath);
                return Optional.empty();
            }

            double seconds = Double.parseDouble(output);
            return Optional.of(Duration.ofMillis(Math.round(seconds * 1000)));
        } catch (IOException | NumberFormatException e) {
            logWarningOnce("ffprobe 재생 시간 확인 중 오류가 발생해 영상 길이 제한을 fail-open으로 통과시킵니다. path={}", videoPath);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
