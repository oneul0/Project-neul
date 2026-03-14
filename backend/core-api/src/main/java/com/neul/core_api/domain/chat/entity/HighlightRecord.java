package com.neul.core_api.domain.chat.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table("highlight_records")
public class HighlightRecord {
    @Id
    private Long id;
    private String roomId;
    private String emotionType;
    private Double peakScore;
    private String topMessage;
    private String liveImageUrl;
    private LocalDateTime timestamp;
}
