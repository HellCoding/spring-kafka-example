package com.example.order.event;

import java.math.BigDecimal;

/**
 * 주문 생성 시 Kafka로 발행되는 이벤트.
 */
public class OrderCreatedEvent {
    private Long orderId;
    private String productName;
    private Integer quantity;
    private BigDecimal totalAmount;

    public OrderCreatedEvent() {}

    public OrderCreatedEvent(Long orderId, String productName, Integer quantity, BigDecimal totalAmount) {
        this.orderId = orderId;
        this.productName = productName;
        this.quantity = quantity;
        this.totalAmount = totalAmount;
    }

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }

    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
}
