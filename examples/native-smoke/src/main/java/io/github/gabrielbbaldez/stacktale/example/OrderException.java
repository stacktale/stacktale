package io.github.gabrielbbaldez.stacktale.example;

/**
 * A domain exception carrying business state — exactly what stacktale's {@code fields:}
 * section surfaces. Registered for reflection in
 * {@code META-INF/native-image/.../reflect-config.json} so the getters remain visible
 * under native-image (the escape hatch documented in docs/native.md).
 */
public class OrderException extends RuntimeException {

    private final int orderId;
    private final boolean retryable;

    public OrderException(int orderId, boolean retryable) {
        super("order " + orderId + " failed at gateway");
        this.orderId = orderId;
        this.retryable = retryable;
    }

    public int getOrderId() {
        return orderId;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
