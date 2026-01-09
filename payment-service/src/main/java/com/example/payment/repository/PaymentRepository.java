package com.example.payment.repository;

import com.example.payment.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 결제 엔티티의 데이터 접근 레이어.
 */
public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByOrderId(Long orderId);
}
