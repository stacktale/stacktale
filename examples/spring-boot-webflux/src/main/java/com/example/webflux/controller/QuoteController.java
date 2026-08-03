package com.example.webflux.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
public class QuoteController {

    private static final Logger log = LoggerFactory.getLogger(QuoteController.class);

    @GetMapping("/quotes/{id}")
    public Mono<String> quote(@PathVariable int id) {
        log.info("quote requested for instrument {}", id);
        return Mono.just(id)
                .subscribeOn(Schedulers.boundedElastic())          // thread hop #1
                .map(i -> {
                    log.info("pricing lookup for instrument {}", i); // logged on boundedElastic thread
                    return i;
                })
                .publishOn(Schedulers.parallel())                   // thread hop #2
                .flatMap(i -> {
                    try {
                        throw new IllegalStateException("pricing feed disconnected");
                    } catch (Exception e) {
                        log.error("quote failed for instrument {}", i, e);
                        return Mono.error(e);
                    }
                });
    }
}
