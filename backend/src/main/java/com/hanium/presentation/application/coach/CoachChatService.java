package com.hanium.presentation.application.coach;

import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
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

    public CoachChatService(
            AnalysisJobRepository analysisJobRepository,
            CoachConversationRepository coachConversationRepository,
            CoachMessageRepository coachMessageRepository,
            FilePathGenerator filePathGenerator,
            JsonFileStorage jsonFileStorage,
            OpenAiCoachClient openAiCoachClient,
            UserRateLimiter userRateLimiter
    ) {
        this.analysisJobRepository = analysisJobRepository;
        this.coachConversationRepository = coachConversationRepository;
        this.coachMessageRepository = coachMessageRepository;
        this.filePathGenerator = filePathGenerator;
        this.jsonFileStorage = jsonFileStorage;
        this.openAiCoachClient = openAiCoachClient;
        this.userRateLimiter = userRateLimiter;
    }

    @Transactional(readOnly = true)
    public CoachConversationResponse getMessages(String jobId, Long ownerId) {
        validateOwnership(jobId, ownerId);

        return coachConversationRepository.findByJobId(jobId)
                .map(conversation -> CoachConversationResponse.from(
                        coachMessageRepository.findAllByConversationIdOrderByCreatedAtAsc(conversation.getId())
                ))
                .orElseGet(() -> CoachConversationResponse.from(List.of()));
    }

    @Transactional
    public CoachConversationResponse sendMessage(String jobId, Long ownerId, String content) {
        AnalysisJob analysisJob = validateOwnership(jobId, ownerId);

        if (!analysisJob.isCompleted()) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "완료된 분석 작업에만 AI 코치를 사용할 수 있습니다."
            );
        }

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
        Map<String, Object> compactAnalysis = loadCompactAnalysis(jobId);

        coachMessageRepository.save(CoachMessage.userMessage(conversation.getId(), content));

        OpenAiCoachReplyResponse reply = openAiCoachClient.generateReply(
                new OpenAiCoachChatRequest(jobId, compactAnalysis, history, content)
        );

        coachMessageRepository.save(CoachMessage.assistantMessage(
                conversation.getId(),
                reply.replyText(),
                reply.generationMode()
        ));

        List<CoachMessage> messages = coachMessageRepository
                .findAllByConversationIdOrderByCreatedAtAsc(conversation.getId());

        return CoachConversationResponse.from(messages);
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

    private Map<String, Object> loadCompactAnalysis(String jobId) {
        Path compactAnalysisPath = filePathGenerator.generateCompactAnalysisPath(jobId);
        return jsonFileStorage.readJson(compactAnalysisPath, Map.class);
    }
}
