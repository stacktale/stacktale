package io.github.gabrielbbaldez.stacktale.spring.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Minimal demo app: the only stacktale-related thing it has is the starter dependency. */
@SpringBootApplication
public class DemoShopApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoShopApplication.class, args);
    }

    @RestController
    static class CheckoutController {

        private static final Logger log = LoggerFactory.getLogger(CheckoutController.class);

        @GetMapping("/orders/{id}/checkout")
        String checkout(@PathVariable int id) {
            log.info("reserving stock for order {}", id);
            try {
                chargeCard(id);
            } catch (Exception e) {
                log.error("checkout failed for order {}", id, e);
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "checkout failed");
            }
            return "ok";
        }

        private void chargeCard(int orderId) {
            throw new IllegalStateException("payment gateway refused order " + orderId);
        }
    }
}
