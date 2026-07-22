package com.hanium.presentation.domain.coach.repository;

import com.hanium.presentation.domain.coach.entity.CoachMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CoachMessageRepository extends JpaRepository<CoachMessage, Long> {

    List<CoachMessage> findAllByConversationIdOrderByCreatedAtAsc(Long conversationId);

    // 프롬프트에 넣을 최근 대화 이력만 가져올 때 사용 (개수는 서비스 계층에서 슬라이싱)
    List<CoachMessage> findTop20ByConversationIdOrderByCreatedAtDesc(Long conversationId);

    void deleteAllByConversationId(Long conversationId);
}
