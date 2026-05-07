package com.gak.core_api.dev;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gak.core_api.domain.chat.service.DonationService;
import com.gak.core_api.domain.chat.service.StreamRedisService;
import com.gak.core_api.domain.roulette.service.RouletteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 개발 환경 전용 테스트 데이터 시드 컨트롤러.
 *
 * <p>실방송 없이 도네이션·투표 데이터를 Redis에 직접 주입해
 * UI/API를 검증할 수 있게 합니다.</p>
 *
 * <p><b>dev 프로필에서만 활성화됩니다. 프로덕션 빌드에는 포함되지 않습니다.</b></p>
 *
 * <pre>
 * 도네이션 시드:  POST /api/dev/seed/{channelId}/donations?count=10
 * 투표 시드:      POST /api/dev/seed/{channelId}/votes?voters=30
 * 전체 초기화:    DELETE /api/dev/seed/{channelId}
 * 현재 상태 조회: GET /api/dev/seed/{channelId}
 * </pre>
 */
@Slf4j
@ConditionalOnProperty(name = "gak.dev-seed.enabled", havingValue = "true")
@RestController
@RequestMapping("/api/dev/seed")
@RequiredArgsConstructor
public class DevSeedController {

    private final DonationService donationService;
    private final StreamRedisService streamRedisService;
    private final RouletteService rouletteService;
    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    // ─── 샘플 데이터 풀 ───────────────────────────────────────────────────────

    private static final String[] NICKNAMES = {
            "달빛여우", "구름사탕", "바람소리", "별빛이슬", "초코파이",
            "하늘바다", "봄비살구", "눈꽃송이", "노을빛강", "소나기야",
            "민트초코", "아이스티", "여름밤별", "가을단풍", "겨울눈사람",
            "분홍고양이", "파란하늘이", "초록잎새", "노란병아리", "빨간사과"
    };

    private static final String[] MESSAGES = {
            "응원합니다! 오늘도 파이팅!",
            "항상 재밌게 보고 있어요~",
            "최고예요! 사랑해요 ❤️",
            "도네 받아주세요 😊",
            "오늘 방송 너무 재밌어요!",
            "수고하세요 짱짱짱",
            "쭉 응원할게요!!",
            "항상 행복하세요~",
            "오늘도 좋은 방송 감사해요",
            "존경합니다 진짜로"
    };

    private static final int[] AMOUNTS = {1_000, 2_000, 3_000, 5_000, 10_000, 30_000, 50_000, 100_000};

    // ─── 도네이션 시드 ────────────────────────────────────────────────────────

    /**
     * 도네이션 데이터를 시드합니다.
     *
     * @param channelId 채널 ID
     * @param count     주입할 도네이션 수 (기본 10, 최대 50)
     */
    @PostMapping("/{channelId}/donations")
    public Mono<ResponseEntity<Map<String, Object>>> seedDonations(
            @PathVariable String channelId,
            @RequestParam(defaultValue = "10") int count) {

        int safeCount = Math.min(Math.max(count, 1), 50);
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        List<DonationService.DonationEntry> entries = new ArrayList<>(safeCount);

        for (int i = 0; i < safeCount; i++) {
            entries.add(DonationService.DonationEntry.builder()
                    .messageId("dev-" + UUID.randomUUID().toString().substring(0, 8))
                    .donorNickname(NICKNAMES[rng.nextInt(NICKNAMES.length)])
                    .message(MESSAGES[rng.nextInt(MESSAGES.length)])
                    .amount(String.valueOf(AMOUNTS[rng.nextInt(AMOUNTS.length)]))
                    .timestamp(LocalDateTime.now().minusMinutes(rng.nextInt(60)))
                    .build());
        }

        String key = "gak:donations:" + channelId;
        return Flux.fromIterable(entries)
                .flatMap(entry -> {
                    try {
                        return redisTemplate.opsForList()
                                .rightPush(key, objectMapper.writeValueAsString(entry))
                                .then();
                    } catch (Exception e) {
                        return Mono.<Void>error(e);
                    }
                })
                .then(redisTemplate.opsForList().size(key))
                .map(totalSize -> {
                    log.info("[DevSeed] Seeded {} donations for channel {}. Total pool: {}",
                            safeCount, channelId, totalSize);
                    return ResponseEntity.ok(Map.of(
                            "seeded", safeCount,
                            "channelId", channelId,
                            "totalPool", totalSize
                    ));
                });
    }

    // ─── 투표 시드 ────────────────────────────────────────────────────────────

    /**
     * 현재 설정된 투표 항목을 기준으로 가상 투표를 시드합니다.
     * 항목이 없으면 400을 반환합니다.
     *
     * @param channelId 채널 ID
     * @param voters    시드할 투표자 수 (기본 30, 최대 200)
     */
    @PostMapping("/{channelId}/votes")
    public Mono<ResponseEntity<Map<String, Object>>> seedVotes(
            @PathVariable String channelId,
            @RequestParam(defaultValue = "30") int voters) {

        int safeVoters = Math.min(Math.max(voters, 1), 200);

        return streamRedisService.getPollItems(channelId)
                .flatMap(items -> {
                    if (items.isEmpty()) {
                        return Mono.just(ResponseEntity.badRequest().<Map<String, Object>>body(Map.of(
                                "error", "no_items",
                                "message", "투표 항목을 먼저 설정해 주세요. (항목 편집 → 저장)"
                        )));
                    }

                    ThreadLocalRandom rng = ThreadLocalRandom.current();
                    Map<String, Object> tally = new LinkedHashMap<>();
                    for (String item : items) tally.put(item, 0);

                    // 가중 분포: 첫 번째 항목이 약간 더 많이 선택되도록 설정
                    int[] weights = new int[items.size()];
                    int totalWeight = 0;
                    for (int i = 0; i < items.size(); i++) {
                        weights[i] = items.size() - i + 1;
                        totalWeight += weights[i];
                    }
                    final int finalTotalWeight = totalWeight;

                    return Flux.range(0, safeVoters)
                            .flatMap(i -> {
                                // 닉네임 조합 (중복 허용)
                                String senderId = "dev-user-" + String.format("%04d", i);
                                String displayName = NICKNAMES[rng.nextInt(NICKNAMES.length)]
                                        + (i < 10 ? "" : i);

                                // 가중 랜덤 항목 선택
                                int rand = rng.nextInt(finalTotalWeight);
                                int chosen = 0;
                                int cumul = 0;
                                for (int j = 0; j < weights.length; j++) {
                                    cumul += weights[j];
                                    if (rand < cumul) {
                                        chosen = j;
                                        break;
                                    }
                                }
                                // 1-indexed 옵션 문자열로 저장 (실제 채팅 !1 방식과 동일)
                                String voteOption = String.valueOf(chosen + 1);
                                tally.merge(items.get(chosen), 1, (a, b) -> (int) a + (int) b);

                                return streamRedisService.recordVote(channelId, senderId, voteOption)
                                        .then(streamRedisService.recordVoterName(channelId, senderId, displayName))
                                        .then();
                            })
                            .then(Mono.just(ResponseEntity.ok(Map.<String, Object>of(
                                    "seeded", safeVoters,
                                    "channelId", channelId,
                                    "distribution", tally
                            ))));
                });
    }

    // ─── 상태 조회 ────────────────────────────────────────────────────────────

    /**
     * 현재 채널의 시드 데이터 현황을 반환합니다.
     */
    @GetMapping("/{channelId}")
    public Mono<ResponseEntity<Map<String, Object>>> getStatus(@PathVariable String channelId) {
        return Mono.zip(
                redisTemplate.opsForList().size("gak:donations:" + channelId).defaultIfEmpty(0L),
                streamRedisService.getPollItems(channelId),
                streamRedisService.getPollResults(channelId)
        ).map(tuple -> {
            long donationCount = tuple.getT1();
            List<String> items = tuple.getT2();
            Map<Object, Object> rawVotes = tuple.getT3();

            return ResponseEntity.ok(Map.<String, Object>of(
                    "channelId", channelId,
                    "donationPoolSize", donationCount,
                    "pollItems", items,
                    "totalVotes", rawVotes.size()
            ));
        });
    }

    // ─── 룰렛 도네이션 시드 ──────────────────────────────────────────────────────

    /**
     * 현재 설정된 룰렛 항목을 기준으로 도네이션을 시뮬레이션합니다.
     * 항목이 없으면 400을 반환합니다.
     *
     * @param channelId 채널 ID
     * @param count     시드할 도네이션 수 (기본 20, 최대 100)
     */
    @PostMapping("/{channelId}/roulette-donations")
    public Mono<ResponseEntity<Map<String, Object>>> seedRouletteDonations(
            @PathVariable String channelId,
            @RequestParam(defaultValue = "20") int count) {

        int safeCount = Math.min(Math.max(count, 1), 100);

        return rouletteService.getState(channelId)
                .flatMap(state -> {
                    if (state.getItems().isEmpty()) {
                        return Mono.just(ResponseEntity.badRequest().<Map<String, Object>>body(Map.of(
                                "error", "no_items",
                                "message", "룰렛 항목을 먼저 설정해 주세요. (설정 → 저장)"
                        )));
                    }

                    java.util.List<String> items = state.getItems();
                    long rate = state.getRate();
                    ThreadLocalRandom rng = ThreadLocalRandom.current();
                    Map<String, Long> tally = new java.util.LinkedHashMap<>();
                    items.forEach(item -> tally.put(item, 0L));

                    return Flux.range(0, safeCount)
                            .flatMap(i -> {
                                String targetItem = items.get(rng.nextInt(items.size()));
                                long amount = (long) AMOUNTS[rng.nextInt(AMOUNTS.length)];
                                tally.merge(targetItem, amount, Long::sum);
                                String msg = targetItem + " " + MESSAGES[rng.nextInt(MESSAGES.length)];
                                return rouletteService.onDonation(channelId, msg, amount);
                            })
                            .then(Mono.just(ResponseEntity.ok(Map.<String, Object>of(
                                    "seeded", safeCount,
                                    "channelId", channelId,
                                    "rate", rate,
                                    "distribution", tally
                            ))));
                });
    }

    // ─── 전체 초기화 ──────────────────────────────────────────────────────────

    /**
     * 해당 채널의 모든 시드 데이터(도네이션 + 투표 + 투표자 이름 + 룰렛 가중치)를 초기화합니다.
     */
    @DeleteMapping("/{channelId}")
    public Mono<ResponseEntity<Map<String, Object>>> clearAll(@PathVariable String channelId) {
        return donationService.clearDonations(channelId)
                .then(streamRedisService.clearPoll(channelId))
                .then(rouletteService.resetWeights(channelId))
                .then(Mono.just(ResponseEntity.ok(Map.<String, Object>of(
                        "cleared", true,
                        "channelId", channelId
                ))));
    }
}
