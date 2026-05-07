package com.gak.analyzer.optimization.java;

import com.gak.common.dto.RawChatMessage;
import com.gak.analyzer.optimization.OptimizedBatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link JavaChatOptimizer} 단위 테스트.
 *
 * <p>
 * Spring Context 없이 순수 JUnit 5 + AssertJ로만 구성되어 실행 속도가 빠릅니다.
 *
 * <p>
 * 실행:
 * 
 * <pre>
 * .\gradlew :analyzer:test --tests "com.gak.analyzer.optimization.java.JavaChatOptimizerTest"
 * </pre>
 */
class JavaChatOptimizerTest {

    private JavaChatOptimizer optimizer;

    @BeforeEach
    void setUp() {
        optimizer = new JavaChatOptimizer();
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────

    private RawChatMessage msg(String sender, String content) {
        return RawChatMessage.builder()
                .messageId(UUID.randomUUID().toString())
                .roomId("test-room")
                .sender(sender)
                .content(content)
                .timestamp(LocalDateTime.now())
                .build();
    }

    // ── Filter 테스트 (Step A) ───────────────────────────────────────────────

    @Test
    @DisplayName("1자 이하 메시지는 필터링된다")
    void filter_removesShortMessages() {
        List<RawChatMessage> input = List.of(
                msg("user1", "ㅋ"),
                msg("user2", "안녕하세요!"));

        OptimizedBatch result = optimizer.optimize(input);

        assertThat(result.getOriginalCount()).isEqualTo(2);
        assertThat(result.getFilteredCount()).isEqualTo(1);
        assertThat(result.getCompressedChats()).hasSize(1);
        assertThat(result.getCompressedChats().get(0).getContent()).isEqualTo("안녕하세요!");
    }

    @Test
    @DisplayName("이모지만으로 구성된 메시지는 필터링된다")
    void filter_removesEmojiOnlyMessages() {
        List<RawChatMessage> input = List.of(
                msg("user1", "😊👍"),
                msg("user2", "오늘 재밌다"));

        OptimizedBatch result = optimizer.optimize(input);

        assertThat(result.getFilteredCount()).isEqualTo(1);
        assertThat(result.getCompressedChats()).hasSize(1);
        assertThat(result.getCompressedChats().get(0).getContent()).isEqualTo("오늘 재밌다");
    }

    @Test
    @DisplayName("같은 sender의 동일 내용 도배는 첫 번째만 남긴다")
    void filter_removesSenderSpam() {
        List<RawChatMessage> input = List.of(
                msg("spammer", "ㅋㅋㅋ"), // 유지 (첫 번째)
                msg("spammer", "ㅋㅋㅋ"), // 필터링
                msg("spammer", "ㅋㅋㅋ"), // 필터링
                msg("user2", "ㅋㅋㅋ") // 유지 (다른 sender)
        );

        OptimizedBatch result = optimizer.optimize(input);

        // 4개 중 2개 필터링 → 2개 통과 → 동일 내용이라 1개로 압축
        assertThat(result.getOriginalCount()).isEqualTo(4);
        assertThat(result.getFilteredCount()).isEqualTo(2);
        assertThat(result.getCompressedChats()).hasSize(1);
        assertThat(result.getCompressedChats().get(0).getCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("다른 sender의 메시지는 내용이 같아도 필터링되지 않는다")
    void filter_preservesDifferentSendersWithSameContent() {
        List<RawChatMessage> input = List.of(
                msg("user1", "화이팅!"),
                msg("user2", "화이팅!"),
                msg("user3", "화이팅!"));

        OptimizedBatch result = optimizer.optimize(input);

        // 필터링 없음 (서로 다른 sender), 압축에서 1건으로 묶임
        assertThat(result.getFilteredCount()).isEqualTo(0);
        assertThat(result.getCompressedChats()).hasSize(1);
        assertThat(result.getCompressedChats().get(0).getCount()).isEqualTo(3);
    }

    // ── Compress 테스트 (Step B) ─────────────────────────────────────────────

    @Test
    @DisplayName("동일한 내용의 메시지는 1개로 압축되며 count가 정확하다")
    void compress_groupsIdenticalContent() {
        List<RawChatMessage> input = List.of(
                msg("user1", "ㅋㅋㅋ"),
                msg("user2", "ㅋㅋㅋ"),
                msg("user3", "ㅋㅋㅋ"),
                msg("user4", "ㅋㅋㅋ"),
                msg("user5", "ㅋㅋㅋ"));

        OptimizedBatch result = optimizer.optimize(input);

        assertThat(result.getCompressedChats()).hasSize(1);
        assertThat(result.getCompressedChats().get(0).getCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("고유한 메시지는 각각 별도의 CompressedChat으로 유지된다")
    void compress_preservesUniqueMessages() {
        List<RawChatMessage> input = List.of(
                msg("user1", "안녕!"),
                msg("user2", "오늘 재밌다"),
                msg("user3", "화이팅!"));

        OptimizedBatch result = optimizer.optimize(input);

        assertThat(result.getCompressedChats()).hasSize(3);
        result.getCompressedChats().forEach(chat -> assertThat(chat.getCount()).isEqualTo(1));
    }

    @Test
    @DisplayName("대소문자/공백 차이는 동일 그룹으로 처리된다")
    void compress_normalizesContentForGrouping() {
        List<RawChatMessage> input = List.of(
                msg("user1", "화이팅"),
                msg("user2", "화이팅  "), // 뒤 공백
                msg("user3", "  화이팅") // 앞 공백
        );

        OptimizedBatch result = optimizer.optimize(input);

        // normalize 로직으로 동일 그룹 → 1개, count=3
        assertThat(result.getCompressedChats()).hasSize(1);
        assertThat(result.getCompressedChats().get(0).getCount()).isEqualTo(3);
        // 원본 content는 첫 번째 메시지의 것을 사용
        assertThat(result.getCompressedChats().get(0).getContent()).isEqualTo("화이팅");
    }

    // ── 압축률 테스트 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("압축률이 정확하게 계산된다")
    void optimize_calculatesCompressionRatio() {
        // 10개 → 최종 2개 → (1 - 2/10) * 100 = 80%
        List<RawChatMessage> input = List.of(
                msg("u1", "ㅋㅋㅋ"), msg("u2", "ㅋㅋㅋ"), msg("u3", "ㅋㅋㅋ"),
                msg("u4", "ㅋㅋㅋ"), msg("u5", "ㅋㅋㅋ"),
                msg("u6", "오늘 재밌다"), msg("u7", "오늘 재밌다"), msg("u8", "오늘 재밌다"),
                msg("u9", "오늘 재밌다"), msg("u10", "오늘 재밌다"));

        OptimizedBatch result = optimizer.optimize(input);

        assertThat(result.getOriginalCount()).isEqualTo(10);
        assertThat(result.getCompressedChats()).hasSize(2);
        assertThat(result.getCompressionRatio()).isEqualTo(80.0);
    }

    @Test
    @DisplayName("빈 배치 입력 시 빈 결과와 0% 압축률을 반환한다")
    void optimize_handlesEmptyInput() {
        OptimizedBatch result = optimizer.optimize(List.of());

        assertThat(result.getOriginalCount()).isEqualTo(0);
        assertThat(result.getFilteredCount()).isEqualTo(0);
        assertThat(result.getCompressedChats()).isEmpty();
        assertThat(result.getCompressionRatio()).isEqualTo(0.0);
    }
}
