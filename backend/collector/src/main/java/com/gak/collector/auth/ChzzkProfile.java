package com.gak.collector.auth;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ChzzkProfile {
    private final String channelId;
    private final String channelName;
}
