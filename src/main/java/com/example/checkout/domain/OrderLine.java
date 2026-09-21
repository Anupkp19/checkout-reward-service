package com.example.checkout.domain;

import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "order_lines")
public class OrderLine {
    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(nullable = false)
    private String productId;

    @Column(nullable = false)
    private String productName;

    @Column(nullable = false)
    private long unitPriceCents;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private long lineTotalCents;

    public OrderLine() { this.id = UUID.randomUUID().toString(); }

    public OrderLine(Order order, String productId, String productName,
                     long unitPriceCents, int quantity) {
        this();
        this.order = order;
        this.productId = productId;
        this.productName = productName;
        this.unitPriceCents = unitPriceCents;
        this.quantity = quantity;
        this.lineTotalCents = Math.multiplyExact(unitPriceCents, (long) quantity);
    }

    public String getId() { return id; }
    public Order getOrder() { return order; }
    public String getProductId() { return productId; }
    public String getProductName() { return productName; }
    public long getUnitPriceCents() { return unitPriceCents; }
    public int getQuantity() { return quantity; }
    public long getLineTotalCents() { return lineTotalCents; }
}
