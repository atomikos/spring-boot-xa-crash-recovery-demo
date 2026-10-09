package com.atomikos.demo;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;

/**
 * A shipment, created when the order message arrives. Every order should
 * have exactly one.
 */
@Entity
public class Shipment {

    @Id
    @GeneratedValue
    private Long id;

    private Long orderId;

    protected Shipment() {
    }

    public Shipment(Long orderId) {
        this.orderId = orderId;
    }

    public Long getOrderId() {
        return orderId;
    }

}
