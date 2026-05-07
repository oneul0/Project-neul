package com.gak.analyzer.optimization.java;

import com.gak.common.dto.RawChatMessage;
import com.gak.analyzer.optimization.ChatOptimizer;
import com.gak.analyzer.optimization.CompressedChat;
import com.gak.analyzer.optimization.OptimizedBatch;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * {@link ChatOptimizer} Java 구현체 (Adapter).
 *
 * <p>
 * 두 단계로 채팅 배치를 최적화합니다.
 * <ol>
 * <li><b>Step A (Filter)</b>: 스팸·노이즈 메시지 제거</li>
 * <li><b>Step B (Compress)</b>: 동일 내용 그룹핑 → 대표 1건 + count</li>
 * </ol>
 *
 * <p>
 * 추후 Rust/JNI
 * 구현체({@link com.gak.analyzer.optimization.jni.RustChatOptimizer})로
 * 교체할 때는 {@code application.yaml}의 {@code app.optimizer.engine} 값만 변경하면 되며,
 * 이 클래스의 코드는 수정될 필요가 없습니다.
 *
 * @see com.gak.analyzer.optimization.ChatOptimizerConfig
 */
@Slf4j
public class JavaChatOptimizer implements ChatOptimizer {

    /** 기술적 노이즈(UUID, 긴 헥사코드 등)를 감지하는 패턴 */
    private static final Pattern TECHNICAL_NOISE_PATTERN = Pattern.compile(".*[a-fA-F0-0]{8}-[a-fA-F0-0]{4}-[a-fA-F0-0]{4}.*|.*[a-fA-F0-9]{32}.*");

    /** 필터링 기준: 이 길이 미만의 메시지는 제거 */
    private static final int MIN_CONTENT_LENGTH = 1; // 1글자(이모지 등) 허용

    @Override
    public OptimizedBatch optimize(List<RawChatMessage> rawMessages) {
        int originalCount = rawMessages.size();

        // Step A: 필터링
        List<RawChatMessage> filtered = filter(rawMessages);
        int filteredCount = originalCount - filtered.size();

        // Step B: 압축
        List<CompressedChat> compressed = compress(filtered);

        double compressionRatio = (originalCount == 0)
                ? 0.0
                : (1.0 - (double) compressed.size() / originalCount) * 100.0;

        log.info("[Optimizer] original={}, filtered={}, compressed={}, reduction={}%",
                originalCount, filteredCount, compressed.size(),
                String.format("%.1f", compressionRatio));

        return OptimizedBatch.builder()
                .compressedChats(compressed)
                .originalCount(originalCount)
                .filteredCount(filteredCount)
                .compressionRatio(compressionRatio)
                .build();
    }

    // ── Step A: Filter ──────────────────────────────────────────────────────

    private List<RawChatMessage> filter(List<RawChatMessage> messages) {
        // sender + content 조합으로 배치 내 도배 감지
        Set<String> seenSenderContent = new HashSet<>();

        return messages.stream()
                .filter(msg -> {
                    String content = msg.getContent();
                    if (content == null)
                        return false;

                    String trimmed = content.trim();

                    // Rule 1: 너무 짧은 메시지 제거 (1자 이하)
                    if (trimmed.length() <= 1)
                        return false;

                    // Rule 2: 이모지로만 구성된 메시지 제거
                    if (isEmojiOnly(trimmed))
                        return false;

                    // Rule 3: 기술적 노이즈(아이디, UUID 등) 제거
                    if (TECHNICAL_NOISE_PATTERN.matcher(trimmed).find())
                        return false;

                    // Rule 4: 동일 sender의 같은 내용 도배 제거 (배치 내 첫 번째만 유지)
                    String spamKey = msg.getSender() + "::" + trimmed;
                    return seenSenderContent.add(spamKey);
                })
                .collect(Collectors.toList());
    }

    // ── Step B: Compress ────────────────────────────────────────────────────

    private List<CompressedChat> compress(List<RawChatMessage> messages) {
        // 정규화된 content를 key로 그룹핑 (삽입 순서 유지)
        Map<String, List<RawChatMessage>> groups = new LinkedHashMap<>();
        for (RawChatMessage msg : messages) {
            String key = normalize(msg.getContent());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(msg);
        }

        return groups.values().stream()
                .map(group -> {
                    RawChatMessage rep = group.get(0); // 그룹 대표 메시지
                    return CompressedChat.builder()
                            .representativeId(rep.getMessageId())
                            .representativeSenderId(rep.getSenderId()) // Added for Phase 23
                            .roomId(rep.getRoomId())
                            .content(rep.getContent())
                            .count(group.size())
                            .timestamp(rep.getTimestamp())
                            .build();
                })
                .collect(Collectors.toList());
    }

    /**
     * 문자열이 오직 이모지/특수기호로만 구성되어 있는지 확인합니다.
     */
    private boolean isEmojiOnly(String text) {
        if (text == null || text.isBlank()) return false;
        // 한글(완성형+자음/모음), 영문, 숫자가 하나라도 포함되어 있으면 이모지 전용이 아님
        return !text.matches(".*[ㄱ-ㅎㅏ-ㅣ가-힣a-zA-Z0-9].*");
    }

    /**
     * 그룹핑용 정규화: 공백 정리 + 소문자 변환.
     * 원본 content는 변경하지 않으며, 오직 그룹핑 key로만 사용됩니다.
     */
    private String normalize(String content) {
        if (content == null)
            return "";
        return content.trim().replaceAll("\\s+", " ").toLowerCase();
    }
}
