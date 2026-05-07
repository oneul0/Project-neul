package com.gak.v2.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class V2ContextResult {
    private String roomId;
    private LocalDateTime windowStart;
    private LocalDateTime windowEnd;
    private List<AnchorChat> anchors;
    private List<String> keywords;
    private String topicLabel;
}
