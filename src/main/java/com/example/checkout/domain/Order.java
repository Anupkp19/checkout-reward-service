package com.example.checkout.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "orders")
public class Order {
    @Id
    private String id;

    @Column(name = "cart_id", nullable = false, unique = true)
    private String cartId;

    @Column(nullable = false)
    private Instant placedAt = Instant.now();

    @Column(nullable = false)
    private long grossCents;

    @Column(nullable = false)
    private long discountCents;

    @Column(nullable = false)
    private long netCents;

    @Column(length = 64)
    private String couponCode;

    @Column
    private Integer couponPercent;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderLine> lines = new ArrayList<>();

    public Order() { this.id = "ord_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20); }

    public String getId() { return id; }
    public String getCartId() { return cartId; }
    public void setCartId(String cartId) { this.cartId = cartId; }
    public Instant getPlacedAt() { return placedAt; }
    public long getGrossCents() { return grossCents; }
    public void setGrossCents(long grossCents) { this.grossCents = grossCents; }
    public long getDiscountCents() { return discountCents; }
    public void setDiscountCents(long discountCents) { this.discountCents = discountCents; }
    public long getNetCents() { return netCents; }
    public void setNetCents(long netCents) { this.netCents = netCents; }
    public String getCouponCode() { return couponCode; }
    public void setCouponCode(String couponCode) { this.couponCode = couponCode; }
    public Integer getCouponPercent() { return couponPercent; }
    public void setCouponPercent(Integer couponPercent) { this.couponPercent = couponPercent; }
    public List<OrderLine> getLines() { return lines; }
}
