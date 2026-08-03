package com.example.demo.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
public class OrderConfirmationException extends RuntimeException {

    private final int orderId;

    public OrderConfirmationException(int orderId, Throwable cause) {
        super("confirmation aborted for order " + orderId, cause);
        this.orderId = orderId;
    }

    public int getOrderId() {
        return orderId;
    }

    public boolean isRetryable() {
        return false;
    }

    public String getFailedStep() {
        return "send-confirmation-email";
    }
}
