package com.neul.core_api.domain.chat.controller;

import com.neul.core_api.domain.chat.service.DonationService;
import com.neul.core_api.domain.chat.service.DonationService.DonationEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequestMapping("/api/v1/donations")
@RequiredArgsConstructor
public class DonationController {

    private final DonationService donationService;

    @GetMapping("/{channelId}")
    public Flux<DonationEntry> getDonations(@PathVariable String channelId) {
        return donationService.getDonations(channelId);
    }

    @PostMapping("/{channelId}/spin")
    public Mono<DonationEntry> spin(@PathVariable String channelId) {
        return donationService.spin(channelId)
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "후원자가 없습니다.")));
    }

    @DeleteMapping("/{channelId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> clear(@PathVariable String channelId) {
        return donationService.clearDonations(channelId);
    }
}
