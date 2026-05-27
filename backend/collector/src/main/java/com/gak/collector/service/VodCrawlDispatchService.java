package com.gak.collector.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * VOD 크롤 디스패치 서비스.
 *
 * <p>중복 가드 → markRequested → fire-and-forget 크롤 실행을 캡슐화한다.
 * {@code crawlFullVodChat(...).subscribe()} 의 비동기 구독이 서비스 내부에
 * 명시적으로 격리되어, 컨트롤러는 HTTP 매핑에만 집중할 수 있다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VodCrawlDispatchService {

    private final VodAnalysisStatusService vodAnalysisStatusService;
    private final VodChatCrawlerService vodChatCrawlerService;

    /**
     * 크롤 요청을 수락하고 즉시 응답 문자열을 반환한다.
     * 실제 크롤은 비동기 백그라운드(fire-and-forget)로 실행된다.
     */
    public Mono<String> dispatch(String videoNo) {
        if (vodAnalysisStatusService.isProcessing(videoNo)) {
            log.info("[VodCrawlDispatchService] Skip duplicate crawl request for videoNo={}", videoNo);
            return Mono.just("Analysis is already running for videoNo: " + videoNo);
        }

        log.info("[VodCrawlDispatchService] Accepted crawl request for videoNo={}", videoNo);
        vodAnalysisStatusService.markRequested(videoNo);

        // fire-and-forget: 크롤 완료/실패 시 상태 전이, 컨트롤러는 즉시 응답 반환
        vodChatCrawlerService.crawlFullVodChat(videoNo)
                .doOnSuccess(progress -> {
                    log.info("[VodCrawlDispatchService] Finished collection for videoNo={}, pages={}, chats={}",
                            videoNo, progress.pagesProcessed(), progress.chatsCollected());
                    vodAnalysisStatusService.markAnalyzing(
                            videoNo, progress.pagesProcessed(), progress.chatsCollected());
                })
                .doOnError(error -> vodAnalysisStatusService.markFailed(videoNo, error.getMessage()))
                .subscribe();

        return Mono.just("Crawl started for videoNo: " + videoNo);
    }
}
