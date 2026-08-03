package com.example.webflux;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest
@AutoConfigureWebTestClient
class ReactiveDemoApplicationTests {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void quoteEndpointReturns5xxErrorOnDisconnect() {
        webTestClient.get()
                .uri("/quotes/314")
                .exchange()
                .expectStatus()
                .is5xxServerError();
    }
}
