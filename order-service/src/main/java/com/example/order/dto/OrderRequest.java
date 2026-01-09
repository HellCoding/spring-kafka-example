package com.example.order.dto;

import java.math.BigDecimal;

/**
 * 주문 생성 요청 DTO.
 */
public class OrderRequest {
    private String productName;
    private Integer quantity;
    private BigDecimal price;

    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
}
