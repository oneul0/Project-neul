package com.neul.v2.aggregate;

import com.neul.v2.common.dto.MentalBufferState;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class V2EmaBufferService {

    private static final double ALPHA = 0.2;
    private final Map<String, MentalBufferState> roomBuffers = new ConcurrentHashMap<>();

    public MentalBufferState update(String roomId, double rawPositive, double rawNegative) {
        return roomBuffers.compute(roomId, (key, previous) -> {
            double emaPositive = previous == null
                    ? rawPositive
                    : (ALPHA * rawPositive) + ((1 - ALPHA) * previous.getEmaPositive());
            double emaNegative = previous == null
                    ? rawNegative
                    : (ALPHA * rawNegative) + ((1 - ALPHA) * previous.getEmaNegative());

            return MentalBufferState.builder()
                    .roomId(roomId)
                    .rawPositive(rawPositive)
                    .rawNegative(rawNegative)
                    .emaPositive(emaPositive)
                    .emaNegative(emaNegative)
                    .updatedAt(LocalDateTime.now())
                    .build();
        });
    }

    public MentalBufferState get(String roomId) {
        return roomBuffers.get(roomId);
    }
}
