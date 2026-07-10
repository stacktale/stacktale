package io.github.gabrielbbaldez.stacktale.agent.fixture;

/** Instrumented fixture: fails deep inside with meaningful arguments — and logs nothing. */
public final class OrderFlow {

    public record Customer(String email) {}

    public String confirm(int orderId, Customer customer, boolean express) {
        return sendConfirmation(orderId, customer, express ? "EXPRESS" : "STANDARD");
    }

    private String sendConfirmation(int orderId, Customer customer, String tier) {
        return "sent to " + customer.email() + " for " + orderId + " (" + tier + ")";
    }
}
