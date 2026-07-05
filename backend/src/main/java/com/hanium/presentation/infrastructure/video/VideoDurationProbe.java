package com.hanium.presentation.infrastructure.video;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

public interface VideoDurationProbe {

    // 재생 시간을 확인할 수 없으면 Optional.empty()를 반환합니다.
    // 호출자는 이를 fail-open으로 처리해 업로드 차단 근거로 쓰지 않습니다.
    Optional<Duration> probe(Path videoPath);
}
