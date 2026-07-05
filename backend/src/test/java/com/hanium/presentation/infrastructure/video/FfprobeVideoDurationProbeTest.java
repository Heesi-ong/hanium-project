package com.hanium.presentation.infrastructure.video;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FfprobeVideoDurationProbeTest {

    @Test
    void probeReturnsEmptyWhenFfprobeCannotReadVideo() throws Exception {
        assumeTrue(isFfprobeAvailable(), "ffprobe not available");

        FfprobeVideoDurationProbe probe = new FfprobeVideoDurationProbe();

        assertThat(probe.probe(Path.of("missing-video-file.mp4"))).isEqualTo(Optional.empty());
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
