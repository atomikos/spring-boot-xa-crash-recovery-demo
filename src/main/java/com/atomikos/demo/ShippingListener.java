package com.atomikos.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

/**
 * The other side of the queue: ships every order it hears about.
 */
@Component
public class ShippingListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(ShippingListener.class);

    private final ShipmentRepository shipments;

    public ShippingListener(ShipmentRepository shipments) {
        this.shipments = shipments;
    }

    @JmsListener(destination = "orders")
    public void onOrderPlaced(Long orderId) {
        shipments.save(new Shipment(orderId));
        LOGGER.info("📦 shipped order #{}", orderId);
    }

}
