package com.neul.v2.aggregate;

import com.neul.v2.common.dto.NarrativeBriefing;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class V2BriefingService {

    public NarrativeBriefing create(String roomId, double balance, double emaNegative, int filteredCount, String topAnchor) {
        String summary;

        if (filteredCount > 0 && emaNegative > 0.45) {
            summary = "부정 반응이 일부 공격적인 유저에 집중되고 있어요. 전체 흐름은 필터링을 보며 차분히 보셔도 됩니다.";
        } else if (balance >= 0.6) {
            summary = "전체 반응은 비교적 긍정적이에요. 지금은 좋은 반응을 중심으로 방송 흐름을 이어가도 괜찮습니다.";
        } else if (emaNegative > 0.35) {
            summary = "시청자들이 답답함을 느끼는 구간이에요. 핵심 의견을 확인하고 대응하면 흐름을 되찾기 좋습니다.";
        } else {
            summary = "전체 분위기는 아직 안정적이에요. 큰 흔들림 없이 맥락을 따라가면 됩니다.";
        }

        if (topAnchor != null && !topAnchor.isBlank()) {
            summary = summary + " 대표 의견은 \"" + topAnchor + "\" 쪽에 모이고 있어요.";
        }

        return NarrativeBriefing.builder()
                .roomId(roomId)
                .summary(summary)
                .confidence(0.65)
                .generatedAt(LocalDateTime.now())
                .sourceWindowStart(LocalDateTime.now().minusSeconds(20))
                .sourceWindowEnd(LocalDateTime.now())
                .build();
    }
}
