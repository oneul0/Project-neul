package com.gak.core_api.rag;

import com.gak.core_api.domain.chat.entity.VodHighlight;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class HighlightRetrievalServiceTest {

    private HighlightRetrievalService service;

    @BeforeEach
    void setUp() {
        service = new HighlightRetrievalService(mock(DatabaseClient.class), mock(HighlightEmbeddingService.class));
        ReflectionTestUtils.setField(service, "ratioA", 0.6);
        ReflectionTestUtils.setField(service, "ratioB", 0.2);
    }

    @SuppressWarnings("unchecked")
    private List<VodHighlight> merge(List<VodHighlight> a, List<VodHighlight> b, List<VodHighlight> c, int totalK) {
        return (List<VodHighlight>) ReflectionTestUtils.invokeMethod(service, "merge", a, b, c, totalK);
    }

    private VodHighlight highlight(long id) {
        return VodHighlight.builder().id(id).build();
    }

    @Test
    @DisplayName("세 리스트가 모두 비어 있으면 빈 결과를 반환한다")
    void allEmptyListsReturnsEmpty() {
        List<VodHighlight> result = merge(List.of(), List.of(), List.of(), 10);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("id가 중복되면 첫 번째 출처(A 전략) 항목만 유지된다")
    void duplicateIdsAreDeduplicatedKeepingFirst() {
        VodHighlight fromA = highlight(1L);
        VodHighlight fromB = highlight(1L);
        List<VodHighlight> result = merge(List.of(fromA), List.of(fromB), List.of(), 10);

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isSameAs(fromA);
    }

    @Test
    @DisplayName("totalK보다 많은 결과는 totalK개로 잘린다")
    void resultIsCappedAtTotalK() {
        List<VodHighlight> a = List.of(highlight(1), highlight(2), highlight(3));
        List<VodHighlight> b = List.of(highlight(4), highlight(5));
        List<VodHighlight> c = List.of(highlight(6));

        List<VodHighlight> result = merge(a, b, c, 4);
        assertThat(result).hasSize(4);
    }

    @Test
    @DisplayName("A → B → C 순서로 삽입되어 우선순위가 유지된다")
    void orderIsABeforeBBeforeC() {
        VodHighlight ha = highlight(1);
        VodHighlight hb = highlight(2);
        VodHighlight hc = highlight(3);

        List<VodHighlight> result = merge(List.of(ha), List.of(hb), List.of(hc), 10);

        assertThat(result).containsExactly(ha, hb, hc);
    }

    @Test
    @DisplayName("id가 null인 항목은 결과에서 제외된다")
    void nullIdItemsAreExcluded() {
        VodHighlight withId    = highlight(1);
        VodHighlight withoutId = VodHighlight.builder().build();

        List<VodHighlight> result = merge(List.of(withId, withoutId), List.of(), List.of(), 10);
        assertThat(result).containsExactly(withId);
    }

    @Test
    @DisplayName("결과 수가 totalK 이하이면 그대로 반환한다")
    void resultUnderTotalKIsNotTrimmed() {
        List<VodHighlight> result = merge(List.of(highlight(1), highlight(2)), List.of(), List.of(), 5);
        assertThat(result).hasSize(2);
    }
}
