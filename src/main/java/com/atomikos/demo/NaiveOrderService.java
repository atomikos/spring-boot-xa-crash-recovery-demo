package com.atomikos.demo;

import org.springframework.context.annotation.Profile;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;

/**
 * Act 1: the classic dual write. The insert commits on its own, the send
 * happens on its own - a crash in between leaves an order that never ships.
 */
@Service
@Profile("naive")
public class NaiveOrderService implements OrderService {

    private final OrderRepository orders;
    private final JmsTemplate jms;
    private final CrashSwitch crashSwitch;

    public NaiveOrderService(OrderRepository orders, JmsTemplate jms, CrashSwitch crashSwitch) {
        this.orders = orders;
        this.jms = jms;
        this.crashSwitch = crashSwitch;
    }

    @Override
    public long placeOrder(String product) {
        PurchaseOrder order = orders.save(new PurchaseOrder(product)); // 1. PostgreSQL
        crashSwitch.beforeSend();                                      //    (CRASH_BEFORE_SEND=true)
        jms.convertAndSend(ORDERS_QUEUE, order.getId());               // 2. Artemis
        crashSwitch.beforeCommit();                                    //    (CRASH_BEFORE_COMMIT=true)
        return order.getId();
    }

}
