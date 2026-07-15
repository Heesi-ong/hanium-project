package com.hanium.presentation.domain.coach.repository;

import com.hanium.presentation.domain.coach.entity.CoachConversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CoachConversationRepository extends JpaRepository<CoachConversation, Long> {

    Optional<CoachConversation> findByJobId(String jobId);
}
