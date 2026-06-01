package com.gak.analyzer.service;

import com.gak.common.dto.VodCrawlCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

final class VodAnalysisFinalizer {

    private static final Logger log = LoggerFactory.getLogger(VodAnalysisFinalizer.class);

    private final VodAnalysisEventPublisher eventPublisher;
    private final Map<String, VodHighlightAnalyzer.VideoAggregate> aggregates;
    private final Map<String, VodCrawlCompletedEvent> pendingCompletions = new ConcurrentHashMap<>();
    private final Map<String, Long> finalizeGenerations = new ConcurrentHashMap<>();
    private final ScheduledExecutorService finalizeScheduler;
    private final Executor finalizationExecutor;
    private final Duration finalizeQuietPeriod;
    private final Duration finalizeRetryDelay;
    private final int maxFinalizeRetries;
    private final BiConsumer<VodCrawlCompletedEvent, List<VodHighlightAnalyzer.WindowStats>> finalizationHandler;

    VodAnalysisFinalizer(
            VodAnalysisEventPublisher eventPublisher,
            Map<String, VodHighlightAnalyzer.VideoAggregate> aggregates,
            ScheduledExecutorService finalizeScheduler,
            Executor finalizationExecutor,
            Duration finalizeQuietPeriod,
            Duration finalizeRetryDelay,
            int maxFinalizeRetries,
            BiConsumer<VodCrawlCompletedEvent, List<VodHighlightAnalyzer.WindowStats>> finalizationHandler
    ) {
        this.eventPublisher = eventPublisher;
        this.aggregates = aggregates;
        this.finalizeScheduler = finalizeScheduler;
        this.finalizationExecutor = finalizationExecutor;
        this.finalizeQuietPeriod = finalizeQuietPeriod;
        this.finalizeRetryDelay = finalizeRetryDelay;
        this.maxFinalizeRetries = maxFinalizeRetries;
        this.finalizationHandler = finalizationHandler;
    }

    void onChunkReceived(String videoNo) {
        if (pendingCompletions.containsKey(videoNo)) {
            scheduleFinalize(videoNo);
        }
    }

    void complete(VodCrawlCompletedEvent event) {
        pendingCompletions.put(event.getVideoNo(), event);
        scheduleFinalize(event.getVideoNo());
    }

    private void scheduleFinalize(String videoNo) {
        long generation = finalizeGenerations.merge(videoNo, 1L, Long::sum);
        finalizeScheduler.schedule(
                () -> attemptFinalize(videoNo, generation, 0),
                finalizeRetryDelay.toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    private void attemptFinalize(String videoNo, long generation, int retryCount) {
        if (!Objects.equals(finalizeGenerations.get(videoNo), generation)) {
            return;
        }

        VodCrawlCompletedEvent event = pendingCompletions.get(videoNo);
        if (event == null) {
            finalizeGenerations.remove(videoNo);
            return;
        }

        VodHighlightAnalyzer.VideoAggregate aggregate = aggregates.get(videoNo);
        if (aggregate == null || aggregate.windows().isEmpty()) {
            if (event.getChatsCollected() <= 0 || retryCount >= maxFinalizeRetries) {
                pendingCompletions.remove(videoNo);
                finalizeGenerations.remove(videoNo);
                publishEmptyOrFailure(videoNo, event, "채팅 수집은 완료됐지만 분석용 집계가 준비되지 않아 하이라이트 계산을 종료했습니다.");
                return;
            }

            retry(videoNo, generation, retryCount);
            return;
        }

        if (!aggregate.isQuietFor(finalizeQuietPeriod)) {
            retry(videoNo, generation, retryCount);
            return;
        }

        List<VodHighlightAnalyzer.WindowStats> windows;
        synchronized (aggregate) {
            windows = new ArrayList<>(aggregate.windows().values());
        }

        if (windows.isEmpty()) {
            if (retryCount >= maxFinalizeRetries) {
                pendingCompletions.remove(videoNo);
                finalizeGenerations.remove(videoNo);
                publishEmptyOrFailure(videoNo, event, "채팅 집계 결과가 비어 있어 하이라이트 계산을 완료하지 못했습니다.");
                return;
            }

            retry(videoNo, generation, retryCount);
            return;
        }

        if (!pendingCompletions.remove(videoNo, event)) {
            return;
        }
        aggregates.remove(videoNo, aggregate);
        finalizeGenerations.remove(videoNo);
        finalizationExecutor.execute(() -> finalizationHandler.accept(event, windows));
    }

    private void retry(String videoNo, long generation, int retryCount) {
        finalizeScheduler.schedule(
                () -> attemptFinalize(videoNo, generation, retryCount + 1),
                finalizeRetryDelay.toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    private void publishEmptyOrFailure(String videoNo, VodCrawlCompletedEvent event, String failureReason) {
        try {
            if (event.getChatsCollected() <= 0) {
                eventPublisher.publishCompletion(videoNo, 0, 0);
                log.warn("[Vod-Analyzer] Finalized empty VOD analysis for videoNo={} because no chats were collected.", videoNo);
            } else {
                eventPublisher.publishFailure(event, failureReason);
            }
        } catch (Exception error) {
            log.error("[Vod-Analyzer] Failed to publish terminal event for videoNo={}", videoNo, error);
        }
    }
}
