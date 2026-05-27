package com.gak.core_api.domain.chat.service;

import com.gak.core_api.domain.chat.entity.VodHighlight;
import com.gak.core_api.domain.chat.repository.VodHighlightRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * VOD 하이라이트 조회 서비스.
 *
 * <p>정상 경로: 라이브러리 sync 후 개인화 정렬된 결과 반환.
 * <p>에러 경로: 라이브러리 sync 생략, 기본 정렬(startSeconds ASC) fallback.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VodHighlightQueryService {

    private final UserVodLibraryService userVodLibraryService;
    private final VodHighlightRepository vodHighlightRepository;

    public Flux<VodHighlight> getPersonalizedHighlights(String ownerId, String videoNo) {
        return userVodLibraryService.getPersonalizedHighlights(ownerId, videoNo)
                .collectList()
                .flatMapMany(highlights -> {
                    boolean hasHighlights = !highlights.isEmpty();
                    return userVodLibraryService
                            .syncStatus(ownerId, videoNo, hasHighlights ? "READY" : "VIEWED", hasHighlights)
                            .thenMany(Flux.fromIterable(highlights));
                })
                .onErrorResume(error -> {
                    log.warn("[VodHighlightQueryService] Failed to personalize highlights for videoNo={}, falling back to default order", videoNo, error);
                    return vodHighlightRepository.findAllByVideoNoOrderByStartSecondsAsc(videoNo);
                });
    }
}
