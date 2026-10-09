/*
 * Copyright 2026 Atomikos
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
        crashSwitch.beforeSend();                                      //    ./demo.sh crash before-send
        jms.convertAndSend("orders", order.getId());                   // 2. Artemis
        crashSwitch.beforeCommit();                                    //    ./demo.sh crash before-commit
        return order.getId();
    }

}
