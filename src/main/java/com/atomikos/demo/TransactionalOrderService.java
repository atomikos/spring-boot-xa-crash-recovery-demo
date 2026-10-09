package com.atomikos.demo;

import org.springframework.context.annotation.Profile;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The same code, plus one {@code @Transactional}. With the Atomikos starter
 * (the default), the insert and the send are one XA transaction: both happen,
 * or neither does - even across a crash. Without it (profile {@code local-tx}),
 * {@code @Transactional} only covers the database, and the message goes out
 * whatever happens next.
 */
@Service
@Profile("!naive")
public class TransactionalOrderService implements OrderService {

    private final OrderRepository orders;
    private final JmsTemplate jms;
    private final CrashSwitch crashSwitch;

    public TransactionalOrderService(OrderRepository orders, JmsTemplate jms, CrashSwitch crashSwitch) {
        this.orders = orders;
        this.jms = jms;
        this.crashSwitch = crashSwitch;
    }

    @Override
    @Transactional
    public long placeOrder(String product) {
        PurchaseOrder order = orders.save(new PurchaseOrder(product)); // 1. PostgreSQL
        crashSwitch.beforeSend();                                      //    (CRASH_BEFORE_SEND=true)
        jms.convertAndSend(ORDERS_QUEUE, order.getId());               // 2. Artemis
        crashSwitch.beforeCommit();                                    //    (CRASH_BEFORE_COMMIT=true)
        return order.getId();
    }

}
