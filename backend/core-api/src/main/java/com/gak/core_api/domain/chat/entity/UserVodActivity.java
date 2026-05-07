package com.gak.core_api.domain.chat.entity;

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
@Table("user_vod_activity")
public class UserVodActivity {
    @Id
    private Long id;

    private String ownerId;
    private String videoNo;
    private Long highlightId;
    private String actionType;
    private LocalDateTime createdAt;
}
