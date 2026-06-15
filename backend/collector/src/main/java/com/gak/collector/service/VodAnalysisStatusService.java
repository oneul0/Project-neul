package com.gak.collector.service;

import com.gak.collector.controller.VodAnalysisStatusResponse;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class VodAnalysisStatusService {

    private final ConcurrentMap<String, VodAnalysisStatusResponse> statuses = new ConcurrentHashMap<>();

    public VodAnalysisStatusResponse getStatus(String videoNo) {
        return statuses.getOrDefault(videoNo, VodAnalysisStatusResponse.idle(videoNo));
    }

    public boolean isProcessing(String videoNo) {
        VodAnalysisStatusResponse current = statuses.get(videoNo);
        if (current == null) {
            return false;
        }
        return switch (current.status()) {
            case "REQUESTED", "CRAWLING", "ANALYZING" -> true;
            default -> false;
        };
    }

    public void markRequested(String videoNo) {
        statuses.put(videoNo, new VodAnalysisStatusResponse(
                videoNo,
                "REQUESTED",
                "분석 요청을 접수했습니다.",
                Instant.now(),
                null,
                0,
                0,
                null,
                null
        ));
    }

    public void markCrawling(String videoNo, int pagesProcessed, int chatsCollected) {
        VodAnalysisStatusResponse current = getStatus(videoNo);
        statuses.put(videoNo, new VodAnalysisStatusResponse(
                videoNo,
                "CRAWLING",
                "VOD 전체 채팅을 수집하고 있습니다.",
                current.startedAt() == null ? Instant.now() : current.startedAt(),
                null,
                pagesProcessed,
                chatsCollected,
                null,
                null
        ));
    }

    public void markWaiting(String videoNo, int pagesProcessed, int chatsCollected, String message) {
        VodAnalysisStatusResponse current = getStatus(videoNo);
        statuses.put(videoNo, new VodAnalysisStatusResponse(
                videoNo,
                "CRAWLING",
                message,
                current.startedAt() == null ? Instant.now() : current.startedAt(),
                null,
                pagesProcessed,
                chatsCollected,
                null,
                null
        ));
    }

    public void markAnalyzing(String videoNo, int pagesProcessed, int chatsCollected) {
        VodAnalysisStatusResponse current = getStatus(videoNo);
        statuses.put(videoNo, new VodAnalysisStatusResponse(
                videoNo,
                "ANALYZING",
                "수집한 전체 채팅을 기준으로 하이라이트를 계산하고 있습니다.",
                current.startedAt() == null ? Instant.now() : current.startedAt(),
                null,
                pagesProcessed,
                chatsCollected,
                null,
                null
        ));
    }

    public void markCompleted(String videoNo, int pagesProcessed, int chatsCollected) {
        markCompleted(videoNo, pagesProcessed, chatsCollected, null, null);
    }

    public void markCompleted(
            String videoNo,
            int pagesProcessed,
            int chatsCollected,
            Integer timelinePointsCount,
            Integer highlightsCount
    ) {
        VodAnalysisStatusResponse current = getStatus(videoNo);
        statuses.put(videoNo, new VodAnalysisStatusResponse(
                videoNo,
                "COMPLETED",
                "하이라이트 계산이 완료되었습니다.",
                current.startedAt(),
                Instant.now(),
                pagesProcessed,
                chatsCollected,
                timelinePointsCount,
                highlightsCount
        ));
    }

    public void markFailed(String videoNo, String message) {
        VodAnalysisStatusResponse current = getStatus(videoNo);
        statuses.put(videoNo, new VodAnalysisStatusResponse(
                videoNo,
                "FAILED",
                (message == null || message.isBlank()) ? "VOD 분석 중 오류가 발생했습니다." : message,
                current.startedAt(),
                Instant.now(),
                current.pagesProcessed(),
                current.chatsCollected(),
                current.timelinePointsCount(),
                current.highlightsCount()
        ));
    }
}
