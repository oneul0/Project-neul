package com.neul.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VodCrawlCompletedEvent {
    private String videoNo;
    private int pagesProcessed;
    private int chatsCollected;
}
