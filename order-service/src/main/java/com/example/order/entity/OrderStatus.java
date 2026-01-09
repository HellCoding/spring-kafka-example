package com.example.order.entity;

/**
 * 주문 상태를 나타내는 열거형. PENDING → CONFIRMED 또는 CANCELLED.
 */
public enum OrderStatus {
    PENDING,
    CONFIRMED,
    CANCELLED
}
