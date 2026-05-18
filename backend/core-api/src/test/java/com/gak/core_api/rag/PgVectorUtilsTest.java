package com.gak.core_api.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PgVectorUtilsTest {

    @Test
    @DisplayName("빈 벡터는 []를 반환한다")
    void emptyVector() {
        assertThat(PgVectorUtils.toLiteral(new float[]{})).isEqualTo("[]");
    }

    @Test
    @DisplayName("단일 요소 벡터는 쉼표 없이 반환한다")
    void singleElement() {
        assertThat(PgVectorUtils.toLiteral(new float[]{0.5f})).isEqualTo("[0.5]");
    }

    @Test
    @DisplayName("복수 요소는 쉼표로 구분해 반환한다")
    void multipleElements() {
        String result = PgVectorUtils.toLiteral(new float[]{0.1f, 0.2f, 0.3f});
        assertThat(result).startsWith("[").endsWith("]");
        assertThat(result.split(",")).hasSize(3);
    }

    @Test
    @DisplayName("음수 값도 올바르게 직렬화한다")
    void negativeValues() {
        String result = PgVectorUtils.toLiteral(new float[]{-0.5f, 0.25f});
        assertThat(result).startsWith("[-").contains(",").endsWith("]");
    }

    @Test
    @DisplayName("결과는 [ 로 시작하고 ] 로 끝난다")
    void bracketWrapped() {
        String result = PgVectorUtils.toLiteral(new float[]{1.0f, 2.0f});
        assertThat(result).startsWith("[").endsWith("]");
    }
}
