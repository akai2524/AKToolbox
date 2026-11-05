import dev.akai.toolbox.payment.PaymentEventGate;

public final class PaymentEventGateChecks {
    public static void main(String[] args) {
        PaymentEventGate gate = new PaymentEventGate();
        long now = 10_000_000_000L;
        if (!gate.accept(101, now - 2_000_000_000L, now)) throw new AssertionError("fresh event rejected");
        if (!gate.accept(101, now - 1_000_000_000L, now)) throw new AssertionError("newer event rejected");
        if (gate.accept(101, now - 2_000_000_000L, now)) throw new AssertionError("reordered event accepted");
        if (gate.accept(101, now - 1_000_000_000L, now)) throw new AssertionError("duplicate accepted");
        if (!gate.accept(102, now - 2_000_000_000L, now)) throw new AssertionError("UIDs not independent");
        if (gate.accept(102, now - 5_000_000_001L, now)) throw new AssertionError("stale event accepted");
        if (gate.accept(102, now + 1, now)) throw new AssertionError("future event accepted");
        if (gate.accept(102, 0, now)) throw new AssertionError("missing timestamp accepted");
        System.out.println("Payment event ordering checks passed.");
    }
}
