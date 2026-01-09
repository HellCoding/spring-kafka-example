package com.example.payment.event;

import java.math.BigDecimal;

/**
 * 주문 생성 이벤트를 수신하기 위한 Kafka 이벤트.
 */
public class OrderCreatedEvent {
    private Long orderId;
    private String productName;
    private Integer quantity;
    private BigDecimal totalAmount;

    public OrderCreatedEvent() {}

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }

    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
}
