package com.neul.core_api.e2e;

import com.neul.core_api.domain.chat.entity.VodHighlight;
import com.neul.core_api.domain.chat.entity.VodTimelinePointEntity;
import com.neul.core_api.domain.chat.repository.VodHighlightRepository;
import com.neul.core_api.domain.chat.repository.VodTimelinePointRepository;
import com.neul.core_api.domain.chat.service.VodAnalysisSlotService;
import com.neul.core_api.domain.chat.service.VodAnalysisSlotService.SlotResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("VOD 흐름 및 타임라인 E2E 테스트")
class VodFlowE2ETest extends E2ETestBase {

    @Autowired WebTestClient webTestClient;
    @Autowired VodHighlightRepository vodHighlightRepository;
    @Autowired VodTimelinePointRepository vodTimelinePointRepository;
    @Autowired VodAnalysisSlotService slotService;
    @Autowired ReactiveStringRedisTemplate stringRedisTemplate;

    private static final String V = "test-vod-flow-99999";

    @BeforeEach
    void cleanUp() {
        vodHighlightRepository.deleteAllByVideoNo(V).block();
        vodTimelinePointRepository.deleteAllByVideoNo(V).block();
        for (String suffix : new String[]{"", "-2", "-a", "-b", "-c", "-d"}) {
            stringRedisTemplate.delete("vod:owner:" + V + suffix).block();
        }
        stringRedisTemplate.delete("vod:active:global").block();
        for (String owner : new String[]{"owner-test", "owner-429-test", "owner-a", "owner-b", "owner-c", "owner-d"}) {
            stringRedisTemplate.delete("vod:active:user:" + owner).block();
        }
    }

    // ── GET /highlights ──────────────────────────────────────────────────────

    @Test
    @DisplayName("하이라이트가 없으면 빈 배열 반환")
    void highlights_empty() {
        webTestClient.get()
                .uri("/api/v1/vod/{videoNo}/highlights", V)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(VodHighlight.class)
                .hasSize(0);
    }

    @Test
    @DisplayName("저장된 하이라이트를 startSeconds 오름차순으로 반환")
    void highlights_returnsSortedByStartSeconds() {
        vodHighlightRepository.save(highlight(300, 360, 8.5)).block();
        vodHighlightRepository.save(highlight(60, 120, 7.0)).block();

        webTestClient.get()
                .uri("/api/v1/vod/{videoNo}/highlights", V)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(VodHighlight.class)
                .value(list -> {
                    assertThat(list).hasSize(2);
                    assertThat(list.get(0).getStartSeconds()).isEqualTo(60);
                    assertThat(list.get(1).getStartSeconds()).isEqualTo(300);
                });
    }

    // ── GET /timeline ────────────────────────────────────────────────────────

    @Test
    @DisplayName("타임라인도 하이라이트도 없으면 빈 배열 반환")
    void timeline_allEmpty() {
        webTestClient.get()
                .uri("/api/v1/vod/{videoNo}/timeline", V)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(VodTimelinePointEntity.class)
                .hasSize(0);
    }

    @Test
    @DisplayName("저장된 타임라인을 startSeconds 오름차순으로 반환")
    void timeline_returnsSortedByStartSeconds() {
        vodTimelinePointRepository.save(timelinePoint(120)).block();
        vodTimelinePointRepository.save(timelinePoint(30)).block();

        webTestClient.get()
                .uri("/api/v1/vod/{videoNo}/timeline", V)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(VodTimelinePointEntity.class)
                .value(list -> {
                    assertThat(list).hasSize(2);
                    assertThat(list.get(0).getStartSeconds()).isEqualTo(30);
                    assertThat(list.get(1).getStartSeconds()).isEqualTo(120);
                });
    }

    @Test
    @DisplayName("타임라인이 없으면 하이라이트 기반 폴백으로 반환")
    void timeline_fallbackFromHighlights() {
        vodHighlightRepository.save(highlight(200, 260, 9.0)).block();

        webTestClient.get()
                .uri("/api/v1/vod/{videoNo}/timeline", V)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(VodTimelinePointEntity.class)
                .value(list -> {
                    assertThat(list).hasSize(1);
                    assertThat(list.get(0).getStartSeconds()).isEqualTo(200);
                });
    }

    @Test
    @DisplayName("타임라인이 있으면 하이라이트 폴백을 쓰지 않음")
    void timeline_prefersSavedOverFallback() {
        vodHighlightRepository.save(highlight(500, 560, 9.0)).block();
        vodTimelinePointRepository.save(timelinePoint(100)).block();

        webTestClient.get()
                .uri("/api/v1/vod/{videoNo}/timeline", V)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(VodTimelinePointEntity.class)
                .value(list -> {
                    assertThat(list).hasSize(1);
                    assertThat(list.get(0).getStartSeconds()).isEqualTo(100);
                });
    }

    // ── VodAnalysisSlotService ────────────────────────────────────────────────

    @Test
    @DisplayName("처음 슬롯 요청은 ACQUIRED")
    void slot_firstAcquireSucceeds() {
        StepVerifier.create(slotService.tryAcquire("owner-test", V))
                .expectNext(SlotResult.ACQUIRED)
                .verifyComplete();
    }

    @Test
    @DisplayName("같은 사용자가 중복 요청하면 REJECTED_USER")
    void slot_sameUserRejected() {
        slotService.tryAcquire("owner-test", V).block();

        StepVerifier.create(slotService.tryAcquire("owner-test", V + "-2"))
                .expectNext(SlotResult.REJECTED_USER)
                .verifyComplete();
    }

    @Test
    @DisplayName("슬롯 반납 후 같은 사용자가 다시 ACQUIRED")
    void slot_releaseAndReacquire() {
        slotService.tryAcquire("owner-test", V).block();
        slotService.releaseByVideoNo(V).block();

        StepVerifier.create(slotService.tryAcquire("owner-test", V))
                .expectNext(SlotResult.ACQUIRED)
                .verifyComplete();
    }

    @Test
    @DisplayName("전역 슬롯이 가득 차면 REJECTED_GLOBAL")
    void slot_globalLimitRejected() {
        slotService.tryAcquire("owner-a", V + "-a").block();
        slotService.tryAcquire("owner-b", V + "-b").block();
        slotService.tryAcquire("owner-c", V + "-c").block();

        StepVerifier.create(slotService.tryAcquire("owner-d", V + "-d"))
                .expectNext(SlotResult.REJECTED_GLOBAL)
                .verifyComplete();
    }

    // ── POST /analyze HTTP 응답 ───────────────────────────────────────────────

    @Test
    @DisplayName("전역 슬롯이 가득 차면 POST /analyze → 503")
    void analyzeEndpoint_globalLimitReturns503() {
        stringRedisTemplate.opsForValue().set("vod:active:global", "3").block();
        stringRedisTemplate.expire("vod:active:global", Duration.ofMinutes(1)).block();

        webTestClient.post()
                .uri("/api/v1/vod/{videoNo}/analyze", V)
                .exchange()
                .expectStatus().isEqualTo(503);
    }

    @Test
    @DisplayName("사용자 슬롯이 이미 사용 중이면 POST /analyze → 429")
    void analyzeEndpoint_userLimitReturns429() {
        stringRedisTemplate.opsForValue().set("vod:active:user:owner-429-test", "1").block();
        stringRedisTemplate.expire("vod:active:user:owner-429-test", Duration.ofMinutes(1)).block();

        webTestClient.post()
                .uri("/api/v1/vod/{videoNo}/analyze?ownerId=owner-429-test", V)
                .exchange()
                .expectStatus().isEqualTo(429);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private VodHighlight highlight(int start, int end, double score) {
        return VodHighlight.builder()
                .videoNo(V).startSeconds(start).endSeconds(end)
                .highlightScore(score).category("HYPE")
                .description("테스트 하이라이트").topMessage("테스트 채팅")
                .build();
    }

    private VodTimelinePointEntity timelinePoint(int start) {
        return VodTimelinePointEntity.builder()
                .videoNo(V).startSeconds(start).endSeconds(start + 60)
                .messageCount(100).participantCount(50).activityScore(5.0)
                .build();
    }
}
