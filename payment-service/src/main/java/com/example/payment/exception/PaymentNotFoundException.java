package com.example.payment.exception;

/**
 * 결제 정보를 찾을 수 없을 때 발생하는 예외.
 */
public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(Long orderId) {
        super("Payment not found for order: " + orderId);
    }
}
