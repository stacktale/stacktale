package com.example.demo.web;

import com.example.demo.exception.OrderConfirmationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The one place a failed request is logged. Logging the exception that is actually thrown —
 * the domain wrapper, not the cause it was built from — is what gives the report its
 * {@code wrapped by:} line and the wrapper's {@code fields:}.
 */
@RestControllerAdvice
public class ErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);

    @ExceptionHandler(OrderConfirmationException.class)
    public ResponseEntity<Void> orderConfirmationFailed(OrderConfirmationException e) {
        log.error("Failed to confirm order {}", e.getOrderId(), e);
        return ResponseEntity.internalServerError().build();
    }
}
