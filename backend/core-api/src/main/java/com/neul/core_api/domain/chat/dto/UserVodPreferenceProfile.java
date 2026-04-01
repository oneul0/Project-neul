package com.neul.core_api.domain.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserVodPreferenceProfile {
    private List<String> topCategories;
    private List<String> topReactionLabels;
    private Map<String, Double> categoryAffinity;
    private Map<String, Double> reactionAffinity;
}
