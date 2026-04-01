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
@Table("user_vod_library")
public class UserVodLibraryEntry {
    @Id
    private Long id;

    private String ownerId;
    private String videoNo;
    private String status;
    private LocalDateTime lastViewedAt;
    private LocalDateTime lastAnalyzedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
