package com.example.checkout.domain;

import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "cart_items",
        uniqueConstraints = @UniqueConstraint(columnNames = {"cart_id", "product_id"}))
public class CartItem {
    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cart_id", nullable = false)
    private Cart cart;

    @Column(name = "product_id", nullable = false)
    private String productId;

    @Column(nullable = false)
    private int quantity;

    public CartItem() { this.id = UUID.randomUUID().toString(); }

    public CartItem(Cart cart, String productId, int quantity) {
        this();
        this.cart = cart;
        this.productId = productId;
        this.quantity = quantity;
    }

    public String getId() { return id; }
    public Cart getCart() { return cart; }
    public String getProductId() { return productId; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
}
