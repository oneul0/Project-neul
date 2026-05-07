package com.gak.collector.controller;

import java.time.Instant;

public record VodAnalysisStatusResponse(
        String videoNo,
        String status,
        String message,
        Instant startedAt,
        Instant completedAt,
        Integer pagesProcessed,
        Integer chatsCollected
) {
    public static VodAnalysisStatusResponse idle(String videoNo) {
        return new VodAnalysisStatusResponse(
                videoNo,
                "IDLE",
                "아직 분석을 시작하지 않았습니다.",
                null,
                null,
                0,
                0
        );
    }
}
