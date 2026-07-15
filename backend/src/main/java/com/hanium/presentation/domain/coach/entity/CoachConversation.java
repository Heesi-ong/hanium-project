package com.hanium.presentation.domain.coach.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "ai_coach_conversations")
public class CoachConversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false, unique = true, length = 50)
    private String jobId;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected CoachConversation() {
    }

    private CoachConversation(String jobId, Long ownerId) {
        this.jobId = jobId;
        this.ownerId = ownerId;
        this.createdAt = LocalDateTime.now();
    }

    public static CoachConversation create(String jobId, Long ownerId) {
        return new CoachConversation(jobId, ownerId);
    }

    public Long getId() {
        return id;
    }

    public String getJobId() {
        return jobId;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
