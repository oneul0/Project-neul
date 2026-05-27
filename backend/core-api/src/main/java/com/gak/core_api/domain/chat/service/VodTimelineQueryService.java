package com.gak.core_api.domain.chat.service;

import com.gak.core_api.domain.chat.entity.VodHighlight;
import com.gak.core_api.domain.chat.entity.VodTimelinePointEntity;
import com.gak.core_api.domain.chat.repository.VodHighlightRepository;
import com.gak.core_api.domain.chat.repository.VodTimelinePointRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * VOD 타임라인 조회 서비스.
 *
 * <p>빈 결과: 하이라이트에서 타임라인 포인트를 변환해 반환(fallback).
 * <p>에러: 하이라이트 fallback 시도 → 그것도 에러이면 빈 Flux 반환.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VodTimelineQueryService {

    private final UserVodLibraryService userVodLibraryService;
    private final VodTimelinePointRepository vodTimelinePointRepository;
    private final VodHighlightRepository vodHighlightRepository;

    public Flux<VodTimelinePointEntity> getTimelinePoints(String ownerId, String videoNo) {
        return userVodLibraryService.syncStatus(ownerId, videoNo, "VIEWED", false)
                .thenMany(vodTimelinePointRepository.findAllByVideoNoOrderByStartSecondsAsc(videoNo))
                .collectList()
                .flatMapMany(points -> {
                    if (!points.isEmpty()) {
                        return Flux.fromIterable(points);
                    }
                    log.info("[VodTimelineQueryService] Timeline is empty for videoNo={}, building fallback from highlights", videoNo);
                    return vodHighlightRepository.findAllByVideoNoOrderByStartSecondsAsc(videoNo)
                            .map(this::toFallbackTimelinePoint);
                })
                .onErrorResume(error -> {
                    log.warn("[VodTimelineQueryService] Failed to load timeline for videoNo={}, returning highlight-based fallback", videoNo, error);
                    return vodHighlightRepository.findAllByVideoNoOrderByStartSecondsAsc(videoNo)
                            .map(this::toFallbackTimelinePoint)
                            .onErrorResume(fallbackError -> {
                                log.warn("[VodTimelineQueryService] Failed to build fallback timeline for videoNo={}, returning empty timeline", videoNo, fallbackError);
                                return Flux.empty();
                            });
                });
    }

    /**
     * VodHighlight → VodTimelinePointEntity 변환.
     * score → participantCount/messageCount 휴리스틱은 원래 VodController 로직을 그대로 보존.
     * TODO: 확인 필요 — 휴리스틱 수식이 도메인 정책으로 승격될 경우 별도 value object 도입 검토.
     */
    VodTimelinePointEntity toFallbackTimelinePoint(VodHighlight highlight) {
        double score = highlight.getHighlightScore() != null ? highlight.getHighlightScore() : 0.0d;
        int participantCount = Math.max(1, (int) Math.round(score));
        int messageCount = Math.max(participantCount, (int) Math.round(score * 2));

        return VodTimelinePointEntity.builder()
                .id(highlight.getId())
                .videoNo(highlight.getVideoNo())
                .startSeconds(highlight.getStartSeconds())
                .endSeconds(highlight.getEndSeconds())
                .messageCount(messageCount)
                .participantCount(participantCount)
                .activityScore(score)
                .category(highlight.getCategory())
                .topMessage(highlight.getTopMessage())
                .build();
    }
}
