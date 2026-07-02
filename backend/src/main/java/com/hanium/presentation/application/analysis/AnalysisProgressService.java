package com.hanium.presentation.application.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import com.hanium.presentation.domain.analysis.type.AnalysisStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 분석 파이프라인이 지금 몇 % 진행됐는지를 Redis에 잠깐 저장해두는 서비스입니다.
 *
 * <p>AnalysisJob의 최종 상태(완료/실패)는 지금처럼 MySQL(AnalysisJob 테이블)에 저장되는
 * 것이 원본입니다. 이 서비스는 그 흐름을 대체하지 않고, 분석이 진행되는 "동안" 화면에
 * 진행률을 보여주기 위한 임시 캐시 용도로만 Redis를 사용합니다. 서버가 재시작되거나
 * TTL이 지나 값이 사라져도 서비스 정확성에는 영향이 없습니다.</p>
 */
@Service
public class AnalysisProgressService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisProgressService.class);

    private static final String KEY_PREFIX = "analysis:progress:";
    private static final Duration TTL = Duration.ofHours(2);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private volatile boolean redisWarningLogged = false;

    public AnalysisProgressService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public void start(String jobId) {
        write(jobId, AnalysisStep.UPLOAD, AnalysisStatus.UPLOADED, 0, "분석 대기 중입니다.");
    }

    public void update(
            String jobId,
            AnalysisStep step,
            AnalysisStatus status,
            int percent,
            String message
    ) {
        write(jobId, step, status, percent, message);
    }

    public void complete(String jobId) {
        write(jobId, AnalysisStep.RESULT_SAVE, AnalysisStatus.COMPLETED, 100, "분석이 완료되었습니다.");
    }

    public void fail(String jobId, int lastPercent, String reason) {
        write(
                jobId,
                null,
                AnalysisStatus.FAILED,
                lastPercent,
                reason == null ? "분석 중 오류가 발생했습니다." : reason
        );
    }

    /**
     * 진행률 정보를 반환합니다. Redis에 값이 없으면(캐시가 만료됐거나 아직 시작 전이면) null을
     * 반환하며, 이 경우 호출하는 쪽에서 DB에 저장된 최종 상태(AnalysisStatusResponse)로
     * 대체해서 보여주면 됩니다.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getProgress(String jobId) {
        try {
            String raw = redisTemplate.opsForValue().get(buildKey(jobId));

            if (raw == null || raw.isBlank()) {
                return null;
            }

            Map<String, Object> progress = objectMapper.readValue(raw, Map.class);
            onRedisSuccess();

            return progress;
        } catch (RuntimeException | JsonProcessingException exception) {
            onRedisFailure("진행률 조회(read)", exception);
            return null;
        }
    }

    private void write(
            String jobId,
            AnalysisStep step,
            AnalysisStatus status,
            int percent,
            String message
    ) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("jobId", jobId);
            payload.put("step", step == null ? null : step.name());
            payload.put("stepDescription", step == null ? null : step.getDescription());
            payload.put("status", status.name());
            payload.put("statusDescription", status.getDescription());
            payload.put("percent", Math.max(0, Math.min(percent, 100)));
            payload.put("message", message);
            payload.put("updatedAt", LocalDateTime.now().toString());

            String json = objectMapper.writeValueAsString(payload);

            redisTemplate.opsForValue().set(buildKey(jobId), json, TTL);
            onRedisSuccess();
        } catch (JsonProcessingException | RuntimeException exception) {
            // Redis는 진행률 표시용 보조 캐시일 뿐이므로, 여기서 실패하더라도
            // 실제 분석 파이프라인(analysisJob 상태 저장, 결과 JSON 생성)은 계속 진행되어야 합니다.
            onRedisFailure("진행률 저장(write)", exception);
        }
    }

    private void onRedisFailure(String action, Exception exception) {
        if (!redisWarningLogged) {
            redisWarningLogged = true;
            log.warn(
                    "Redis {} 실패 - 진행률(%) 표시는 저장된 상태 기준의 대략적인 값으로 대체됩니다. "
                            + "Redis가 켜져 있는지, application-local.yml의 spring.data.redis 설정이 맞는지 확인하세요. 원인: {}",
                    action,
                    exception.toString()
            );
        }
    }

    private void onRedisSuccess() {
        if (redisWarningLogged) {
            redisWarningLogged = false;
            log.info("Redis 연결이 정상적으로 복구되어 진행률 캐시를 다시 사용합니다.");
        }
    }

    private String buildKey(String jobId) {
        return KEY_PREFIX + jobId;
    }
}
