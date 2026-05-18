package com.gak.core_api.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gak.core_api.domain.chat.entity.VodHighlight;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HighlightEmbeddingServiceTest {

    private HighlightEmbeddingService service;

    @BeforeEach
    void setUp() {
        WebClient.Builder builder = mock(WebClient.Builder.class);
        when(builder.build()).thenReturn(mock(WebClient.class));
        service = new HighlightEmbeddingService(builder, mock(DatabaseClient.class), new ObjectMapper());
    }

    @Test
    @DisplayName("모든 필드가 null이면 fallback 값으로 임베딩 텍스트를 생성한다")
    void nullFieldsUseFallbacks() {
        VodHighlight h = VodHighlight.builder().build();
        String text = service.buildEmbeddingText(h);

        assertThat(text).contains("unknown");
        assertThat(text).contains("neutral");
        assertThat(text).contains("density=0.0x");
        assertThat(text).contains("hype=0.00");
        assertThat(text).contains("laugh=0.00");
        assertThat(text).contains("surprise=0.00");
        assertThat(text).contains("tension=0.00");
    }

    @Test
    @DisplayName("sceneLabel이 null이면 unknown으로 대체된다")
    void nullSceneLabelBecomesUnknown() {
        VodHighlight h = VodHighlight.builder().sceneLabel(null).category("FPS").build();
        assertThat(service.buildEmbeddingText(h)).startsWith("[unknown]");
    }

    @Test
    @DisplayName("category가 null이면 unknown으로 대체된다")
    void nullCategoryBecomesUnknown() {
        VodHighlight h = VodHighlight.builder().sceneLabel("PEAK").category(null).build();
        assertThat(service.buildEmbeddingText(h)).contains("unknown");
    }

    @Test
    @DisplayName("emotionDominance가 null이면 neutral로 대체된다")
    void nullEmotionDominanceBecomesNeutral() {
        VodHighlight h = VodHighlight.builder().emotionDominance(null).build();
        assertThat(service.buildEmbeddingText(h)).contains("dominant=neutral");
    }

    @Test
    @DisplayName("모든 필드가 있으면 올바른 형식의 텍스트를 생성한다")
    void fullFieldsProducesCorrectFormat() {
        VodHighlight h = VodHighlight.builder()
                .sceneLabel("PEAK")
                .category("FPS")
                .emotionDominance("HYPE")
                .densityRatio(3.5)
                .uniqueUserRatio(0.42)
                .hypeRatio(0.8)
                .laughRatio(0.1)
                .surpriseRatio(0.05)
                .tensionRatio(0.05)
                .keywordSummary("킬 연속 클러치")
                .build();

        String text = service.buildEmbeddingText(h);

        assertThat(text).startsWith("[PEAK]");
        assertThat(text).contains("FPS");
        assertThat(text).contains("dominant=HYPE");
        assertThat(text).contains("density=3.5x");
        assertThat(text).contains("hype=0.80");
        assertThat(text).contains("laugh=0.10");
        assertThat(text).contains("keywords: 킬 연속 클러치");
    }

    @Test
    @DisplayName("blank keywordSummary는 keywords: 뒤에 빈 문자열을 출력한다")
    void blankKeywordSummaryProducesEmptyKeywords() {
        VodHighlight h = VodHighlight.builder().keywordSummary("   ").build();
        assertThat(service.buildEmbeddingText(h)).contains("keywords: ");
    }
}
