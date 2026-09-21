package com.example.checkout.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "coupons")
public class Coupon {
    public enum Status { AVAILABLE, REDEEMED }

    @Id
    @Column(length = 64)
    private String code;

    @Column(nullable = false)
    private int percent;

    @Column(nullable = false, unique = true)
    private long milestone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.AVAILABLE;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column
    private Instant redeemedAt;

    @Column
    private String redeemedOnOrderId;

    @Version
    private long version;

    public Coupon() {}

    public Coupon(String code, int percent, long milestone) {
        this.code = code;
        this.percent = percent;
        this.milestone = milestone;
    }

    public String getCode() { return code; }
    public int getPercent() { return percent; }
    public long getMilestone() { return milestone; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getRedeemedAt() { return redeemedAt; }
    public void setRedeemedAt(Instant redeemedAt) { this.redeemedAt = redeemedAt; }
    public String getRedeemedOnOrderId() { return redeemedOnOrderId; }
    public void setRedeemedOnOrderId(String orderId) { this.redeemedOnOrderId = orderId; }
    public long getVersion() { return version; }
}
