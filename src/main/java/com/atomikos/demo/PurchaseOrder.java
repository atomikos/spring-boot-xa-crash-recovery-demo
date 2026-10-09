package com.atomikos.demo;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;

/**
 * An order, stored in PostgreSQL.
 */
@Entity
public class PurchaseOrder {

    @Id
    @GeneratedValue
    private Long id;

    private String product;

    protected PurchaseOrder() {
    }

    public PurchaseOrder(String product) {
        this.product = product;
    }

    public Long getId() {
        return id;
    }

    public String getProduct() {
        return product;
    }

}
