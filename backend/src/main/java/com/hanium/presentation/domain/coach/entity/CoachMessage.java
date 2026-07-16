package com.hanium.presentation.domain.coach.entity;

import com.hanium.presentation.domain.coach.type.CoachMessageRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "ai_coach_messages")
public class CoachMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CoachMessageRole role;

    @Lob
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "generation_mode", length = 20)
    private String generationMode;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected CoachMessage() {
    }

    private CoachMessage(
            Long conversationId,
            CoachMessageRole role,
            String content,
            String generationMode
    ) {
        this.conversationId = conversationId;
        this.role = role;
        this.content = content;
        this.generationMode = generationMode;
        this.createdAt = LocalDateTime.now();
    }

    public static CoachMessage userMessage(Long conversationId, String content) {
        return new CoachMessage(conversationId, CoachMessageRole.USER, content, null);
    }

    public static CoachMessage assistantMessage(
            Long conversationId,
            String content,
            String generationMode
    ) {
        return new CoachMessage(conversationId, CoachMessageRole.ASSISTANT, content, generationMode);
    }

    public Long getId() {
        return id;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public CoachMessageRole getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public String getGenerationMode() {
        return generationMode;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
