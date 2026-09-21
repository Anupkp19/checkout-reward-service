package com.example.checkout.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "idempotency",
        uniqueConstraints = @UniqueConstraint(columnNames = {"endpoint", "idem_key"}))
public class IdempotencyRecord {
    @Id
    private String id;

    @Column(nullable = false, name = "endpoint")
    private String endpoint;

    @Column(nullable = false, name = "idem_key", length = 128)
    private String key;

    @Column(nullable = false, length = 128)
    private String requestHash;

    @Column(nullable = false)
    private int statusCode;

    @Lob
    @Column(nullable = false)
    private String responseBody;

    @Column
    private String orderId;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public IdempotencyRecord() {}

    public IdempotencyRecord(String id, String endpoint, String key,
                             String requestHash, int statusCode,
                             String responseBody, String orderId) {
        this.id = id;
        this.endpoint = endpoint;
        this.key = key;
        this.requestHash = requestHash;
        this.statusCode = statusCode;
        this.responseBody = responseBody;
        this.orderId = orderId;
    }

    public String getId() { return id; }
    public String getEndpoint() { return endpoint; }
    public String getKey() { return key; }
    public String getRequestHash() { return requestHash; }
    public int getStatusCode() { return statusCode; }
    public String getResponseBody() { return responseBody; }
    public String getOrderId() { return orderId; }
}
