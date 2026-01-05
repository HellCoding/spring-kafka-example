package com.example.order.event;

import java.math.BigDecimal;

public class PaymentEvent {
    private Long paymentId;
    private Long orderId;
    private BigDecimal amount;
    private String status; // SUCCESS or FAILED

    public PaymentEvent() {}

    public Long getPaymentId() { return paymentId; }
    public void setPaymentId(Long paymentId) { this.paymentId = paymentId; }

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
