package io.github.gabrielbbaldez.stacktale.spring.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Minimal reactive demo: logs across operator/scheduler hops, then fails. */
@SpringBootApplication
public class ReactiveDemoApplication {

    @RestController
    static class QuoteController {

        private static final Logger log = LoggerFactory.getLogger(QuoteController.class);

        @GetMapping("/quotes/{id}")
        Mono<String> quote(@PathVariable int id) {
            log.info("quote requested for instrument {}", id);
            return Mono.just(id)
                    .subscribeOn(Schedulers.boundedElastic())          // thread hop #1
                    .map(i -> {
                        log.info("pricing lookup for instrument {}", i); // logged on the elastic thread
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
}
