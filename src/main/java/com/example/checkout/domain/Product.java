package com.example.checkout.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "products")
public class Product {
    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private long unitPriceCents;

    @Column(nullable = false)
    private int inventory;

    @Version
    private long version;

    public Product() {}

    public Product(String id, String name, long unitPriceCents, int inventory) {
        this.id = id;
        this.name = name;
        this.unitPriceCents = unitPriceCents;
        this.inventory = inventory;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public long getUnitPriceCents() { return unitPriceCents; }
    public int getInventory() { return inventory; }
    public long getVersion() { return version; }

    public void setName(String name) { this.name = name; }
    public void setUnitPriceCents(long p) { this.unitPriceCents = p; }
    public void setInventory(int inventory) { this.inventory = inventory; }
}
