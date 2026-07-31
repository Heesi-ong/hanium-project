package com.hanium.presentation.application.coach;

import com.hanium.presentation.common.util.JsonMapSupport;
import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.analysis.type.AnalysisKind;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import com.hanium.presentation.domain.coach.entity.CoachConversation;
import com.hanium.presentation.domain.coach.entity.CoachMessage;
import com.hanium.presentation.domain.coach.repository.CoachConversationRepository;
import com.hanium.presentation.domain.coach.repository.CoachMessageRepository;
import com.hanium.presentation.global.config.UserRateLimiter;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.infrastructure.client.openai.OpenAiCoachClient;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiCoachChatRequest;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiCoachReplyResponse;
import com.hanium.presentation.infrastructure.storage.FilePathGenerator;
import com.hanium.presentation.infrastructure.storage.JsonFileStorage;
import com.hanium.presentation.presentation.dto.response.CoachConversationResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CoachChatService {

    private final AnalysisJobRepository analysisJobRepository;
    private final CoachConversationRepository coachConversationRepository;
    private final CoachMessageRepository coachMessageRepository;
    private final FilePathGenerator filePathGenerator;
    private final JsonFileStorage jsonFileStorage;
    private final OpenAiCoachClient openAiCoachClient;
    private final UserRateLimiter userRateLimiter;
    private final TransactionTemplate transactionTemplate;
    private final int historySummarySize;

    public CoachChatService(
            AnalysisJobRepository analysisJobRepository,
            CoachConversationRepository coachConversationRepository,
            CoachMessageRepository coachMessageRepository,
            FilePathGenerator filePathGenerator,
            JsonFileStorage jsonFileStorage,
            OpenAiCoachClient openAiCoachClient,
            UserRateLimiter userRateLimiter,
            PlatformTransactionManager transactionManager,
            @Value("${coach.history-summary-size:5}") int historySummarySize
    ) {
        this.analysisJobRepository = analysisJobRepository;
        this.coachConversationRepository = coachConversationRepository;
        this.coachMessageRepository = coachMessageRepository;
        this.filePathGenerator = filePathGenerator;
        this.jsonFileStorage = jsonFileStorage;
        this.openAiCoachClient = openAiCoachClient;
        this.userRateLimiter = userRateLimiter;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.historySummarySize = historySummarySize;
    }

    @Transactional(readOnly = true)
    public CoachConversationResponse getMessages(String jobId, Long ownerId) {
        validateOwnership(jobId, ownerId);

        UserRateLimiter.Usage dailyUsage = userRateLimiter.getUsage("coach-daily", ownerId);

        return coachConversationRepository.findByJobId(jobId)
                .map(conversation -> CoachConversationResponse.from(
                        coachMessageRepository.findAllByConversationIdOrderByCreatedAtAsc(conversation.getId()),
                        dailyUsage.used(),
                        dailyUsage.capacity()
                ))
                .orElseGet(() -> CoachConversationResponse.from(
                        List.of(),
                        dailyUsage.used(),
                        dailyUsage.capacity()
                ));
    }

    // 대화 내용만 비우고 CoachConversation 자체는 남겨둡니다(jobId 기준 unique라 재생성할
    // 이유가 없고, 다음 메시지 전송 시 findByJobId로 그대로 재사용됩니다). 일일 메시지 한도는
    // ownerId 기준으로 별도 관리되므로 초기화해도 한도가 리셋되지는 않습니다.
    @Transactional
    public void resetConversation(String jobId, Long ownerId) {
        validateOwnership(jobId, ownerId);

        coachConversationRepository.findByJobId(jobId)
                .ifPresent(conversation ->
                        coachMessageRepository.deleteAllByConversationId(conversation.getId()));
    }

    // OpenAI 호출(openai.timeout-ms, 최대 15초)은 DB 트랜잭션 밖에서 실행합니다. 이 메서드
    // 전체를 하나의 @Transactional로 감싸면, OpenAI 응답이 느려질 때마다 그 시간만큼 Hikari
    // 커넥션을 붙잡고 있게 되어 커넥션 풀(기본 10개)이 동시 코치채팅 요청 몇 건만으로도
    // 고갈되고, 로그인/업로드 등 완전히 무관한 API까지 함께 멈출 수 있습니다. 그래서 DB
    // 작업만 두 개의 짧은 트랜잭션으로 나누고 그 사이에서 OpenAI를 호출합니다.
    // OpenAiCoachClient.generateReply()는 실패해도 예외를 던지지 않고 항상 mock/fallback
    // 응답을 반환하도록 이미 설계돼 있어(내부에서 RuntimeException을 잡아 폴백), 두 번째
    // 트랜잭션이 실행되지 않는 경우는 사실상 없습니다.
    public CoachConversationResponse sendMessage(String jobId, Long ownerId, String content) {
        PreparedCoachMessage prepared = transactionTemplate.execute(
                status -> prepareMessage(jobId, ownerId, content)
        );

        OpenAiCoachReplyResponse reply = openAiCoachClient.generateReply(
                new OpenAiCoachChatRequest(
                        jobId,
                        prepared.compactAnalysis(),
                        prepared.historySummary(),
                        prepared.history(),
                        content
                )
        );

        return transactionTemplate.execute(
                status -> saveReplyAndBuildResponse(prepared.conversationId(), reply, ownerId)
        );
    }

    private PreparedCoachMessage prepareMessage(String jobId, Long ownerId, String content) {
        AnalysisJob analysisJob = validateOwnership(jobId, ownerId);

        if (!analysisJob.isCompleted()) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "완료된 분석 작업에만 AI 코치를 사용할 수 있습니다."
            );
        }

        Map<String, Object> compactAnalysis = loadCompactAnalysis(jobId);
        List<Map<String, Object>> historySummary = loadHistorySummary(ownerId, jobId);

        // 코치 채팅 자체의 사용자별 일일 한도. openai-monthly(전역 예산, OpenAiCoachClient 내부에서
        // 별도 체크)와는 다른 계층의 방어선으로, OpenAI를 호출하기 전에 먼저 통과해야 하는
        // 사용자별 남용 방지 게이트다.
        if (!userRateLimiter.tryConsume("coach-daily", ownerId)) {
            throw new BusinessException(
                    ErrorCode.TOO_MANY_REQUESTS,
                    "AI 코치 일일 메시지 한도를 초과했습니다. 내일 다시 시도해주세요."
            );
        }

        CoachConversation conversation = coachConversationRepository.findByJobId(jobId)
                .orElseGet(() -> coachConversationRepository.save(CoachConversation.create(jobId, ownerId)));

        List<OpenAiCoachChatRequest.ChatTurn> history = loadRecentHistory(conversation.getId());

        coachMessageRepository.save(CoachMessage.userMessage(conversation.getId(), content));

        return new PreparedCoachMessage(conversation.getId(), compactAnalysis, historySummary, history);
    }

    private CoachConversationResponse saveReplyAndBuildResponse(
            Long conversationId,
            OpenAiCoachReplyResponse reply,
            Long ownerId
    ) {
        coachMessageRepository.save(CoachMessage.assistantMessage(
                conversationId,
                reply.replyText(),
                reply.generationMode()
        ));

        List<CoachMessage> messages = coachMessageRepository
                .findAllByConversationIdOrderByCreatedAtAsc(conversationId);
        UserRateLimiter.Usage dailyUsage = userRateLimiter.getUsage("coach-daily", ownerId);

        return CoachConversationResponse.from(messages, dailyUsage.used(), dailyUsage.capacity());
    }

    private record PreparedCoachMessage(
            Long conversationId,
            Map<String, Object> compactAnalysis,
            List<Map<String, Object>> historySummary,
            List<OpenAiCoachChatRequest.ChatTurn> history
    ) {
    }

    private AnalysisJob validateOwnership(String jobId, Long ownerId) {
        AnalysisJob analysisJob = analysisJobRepository.findByJobId(jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ANALYSIS_JOB_NOT_FOUND));

        if (!ownerId.equals(analysisJob.getOwnerId())) {
            throw new BusinessException(ErrorCode.ANALYSIS_JOB_ACCESS_DENIED);
        }

        return analysisJob;
    }

    private List<OpenAiCoachChatRequest.ChatTurn> loadRecentHistory(Long conversationId) {
        List<CoachMessage> recentMessagesDesc = coachMessageRepository
                .findTop20ByConversationIdOrderByCreatedAtDesc(conversationId);

        List<CoachMessage> recentMessagesAsc = new ArrayList<>(recentMessagesDesc);
        Collections.reverse(recentMessagesAsc);

        return recentMessagesAsc.stream()
                .map(message -> new OpenAiCoachChatRequest.ChatTurn(
                        message.getRole().name(),
                        message.getContent()
                ))
                .toList();
    }

    // 현재 발표 외에 같은 사용자의 다른 완료된 발표(재분석 제외) 점수 요약을 최근 순으로
    // 최대 historySummarySize개까지 모읍니다. 토큰 비용을 억제하기 위해 원시 지표/자막/
    // Video LLM 관찰 같은 무거운 데이터는 포함하지 않고 점수만 담습니다.
    private List<Map<String, Object>> loadHistorySummary(Long ownerId, String currentJobId) {
        List<AnalysisJob> pastJobs = analysisJobRepository
                .findByOwnerIdAndStatusAndAnalysisKindAndJobIdNotOrderByCreatedAtDesc(
                        ownerId,
                        AnalysisStatus.COMPLETED,
                        AnalysisKind.STANDARD,
                        currentJobId,
                        PageRequest.of(0, historySummarySize)
                );

        List<Map<String, Object>> summaries = new ArrayList<>();
        for (AnalysisJob pastJob : pastJobs) {
            Map<String, Object> scoreSummary = readScoreSummary(pastJob.getJobId());
            if (scoreSummary == null || scoreSummary.isEmpty()) {
                continue;
            }

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("jobId", pastJob.getJobId());
            entry.put("completedAt", pastJob.getCompletedAt());
            entry.put("scoreSummary", scoreSummary);
            summaries.add(entry);
        }
        return summaries;
    }

    private Map<String, Object> readScoreSummary(String jobId) {
        try {
            Path finalResultPath = filePathGenerator.generateFinalResultPath(jobId);
            Map<String, Object> finalResult = jsonFileStorage.readObjectMap(finalResultPath);
            if (finalResult == null) {
                return null;
            }

            Object scoreSummary = finalResult.get("scoreSummary");
            return scoreSummary instanceof Map<?, ?>
                    ? JsonMapSupport.copyStringKeyedMap(scoreSummary)
                    : null;
        } catch (RuntimeException exception) {
            // 과거 발표 하나의 결과 파일이 손상/누락돼도 히스토리 요약 전체나 현재 코치
            // 채팅이 실패하면 안 되므로, 이 항목만 건너뜁니다.
            return null;
        }
    }

    private Map<String, Object> loadCompactAnalysis(String jobId) {
        Path compactAnalysisPath = filePathGenerator.generateCompactAnalysisPath(jobId);
        try {
            Map<String, Object> compactAnalysis = jsonFileStorage.readObjectMap(compactAnalysisPath);

            if (compactAnalysis == null || compactAnalysis.isEmpty()) {
                throw new BusinessException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "AI 코치에 필요한 분석 요약 데이터를 찾을 수 없습니다. 재분석이 필요할 수 있습니다."
                );
            }

            return compactAnalysis;
        } catch (BusinessException exception) {
            if (exception.getErrorCode() == ErrorCode.INVALID_INPUT_VALUE) {
                throw exception;
            }

            throw new BusinessException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "AI 코치에 필요한 분석 요약 데이터를 찾을 수 없습니다. 재분석이 필요할 수 있습니다."
            );
        }
    }
}
