package com.example.demo.service;

import com.example.demo.exception.OrderConfirmationException;
import com.example.demo.model.Customer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    public void confirmOrder(int orderId) {
        log.info("Processing order confirmation for order {}", orderId);
        Customer customer = fetchCustomer(orderId);

        try {
            // Simulated bug: customer is null due to cache miss, causing NPE on getEmail()
            String email = customer.getEmail();
            log.info("Confirmation email sent to {} for order {}", email, orderId);
        } catch (NullPointerException e) {
            log.error("Failed to process order confirmation for order {}", orderId, e);
            throw new OrderConfirmationException(orderId, e);
        }
    }

    private Customer fetchCustomer(int orderId) {
        log.info("Fetching customer for order {}", orderId);
        log.warn("Cache miss for customer on order {}, returning null", orderId);
        return null;
    }
}
