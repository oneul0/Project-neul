package com.neul.v2.sentiment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neul.common.dto.AnalyzedChatMessage;
import com.neul.common.dto.RawChatMessage;
import com.neul.analyzer.service.HeuristicSentimentAnalyzer;
import com.neul.v2.common.dto.V2RawChatMessage;
import com.neul.v2.common.dto.V2SentimentResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class V2SentimentAgent {

    private final ObjectMapper objectMapper;
    private final HeuristicSentimentAnalyzer heuristicSentimentAnalyzer;
    private final V2SentimentMapper sentimentMapper;
    private final V2SentimentPublisher sentimentPublisher;

    @KafkaListener(
            topics = "v2-raw-chat",
            groupId = "neul-v2-sentiment-group",
            containerFactory = "kafkaListenerContainerFactory")
    public void consume(String json) {
        try {
            V2RawChatMessage rawMessage = objectMapper.readValue(json, V2RawChatMessage.class);
            if (!"CHAT".equals(rawMessage.getMessageType())) {
                return;
            }

            RawChatMessage mapped = sentimentMapper.toRawChatMessage(rawMessage);
            AnalyzedChatMessage analyzed = heuristicSentimentAnalyzer.analyze(mapped);
            V2SentimentResult result = sentimentMapper.toSentimentResult(rawMessage, analyzed);

            sentimentPublisher.publish(result);
            log.debug("[V2SentimentAgent] Published v2 sentiment. roomId={}, messageId={}",
                    result.getRoomId(), result.getMessageId());
        } catch (JsonProcessingException e) {
            log.error("[V2SentimentAgent] Failed to parse v2 raw chat payload", e);
        }
    }
}
