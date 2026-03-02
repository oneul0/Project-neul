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
@Table("analyzed_chats")
public class AnalyzedChat {
    
    @Id
    private Long id;
    
    private String messageId;
    
    private String roomId;
    
    private String content;
    
    private String emotionType;
    
    private Double emotionScore;
    
    private LocalDateTime analyzedAt;
}
