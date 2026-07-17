package com.hanium.presentation.presentation.dto.response;

import org.springframework.data.domain.Page;

import java.util.List;

public record PagedAdminAnalysisJobSummaryResponse(
        List<AdminAnalysisJobSummaryResponse> content,
        long totalElements,
        int totalPages,
        int number,
        int size,
        int numberOfElements,
        boolean first,
        boolean last
) {

    public static PagedAdminAnalysisJobSummaryResponse from(Page<AdminAnalysisJobSummaryResponse> page) {
        return new PagedAdminAnalysisJobSummaryResponse(
                page.getContent(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.getNumber(),
                page.getSize(),
                page.getNumberOfElements(),
                page.isFirst(),
                page.isLast()
        );
    }
}
