package com.example.order.exception;

/**
 * 주문을 찾을 수 없을 때 발생하는 예외.
 */
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(Long orderId) {
        super("Order not found: " + orderId);
    }
}
