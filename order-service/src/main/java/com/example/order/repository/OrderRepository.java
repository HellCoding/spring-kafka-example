package com.example.order.repository;

import com.example.order.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 주문 엔티티의 데이터 접근 레이어.
 */
public interface OrderRepository extends JpaRepository<Order, Long> {
}
