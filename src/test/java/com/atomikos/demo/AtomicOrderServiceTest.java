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

import static com.atomikos.demo.CrashSwitch.CrashPoint.AFTER_PREPARE;
import static com.atomikos.demo.CrashSwitch.CrashPoint.BEFORE_COMMIT;
import static com.atomikos.demo.CrashSwitch.CrashPoint.BEFORE_SEND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * With Atomikos: the order row and the order message are one XA transaction,
 * so a crash leaves neither - and a crash in the middle of the commit is
 * completed by Atomikos.
 */
@SpringBootTest
class AtomicOrderServiceTest extends ContainersTestBase {

    @Test
    void anOrderIsStoredAndShipped() {
        long id = orderService.placeOrder("book");

        assertThat(orders.findById(id)).isPresent();
        await().atMost(Duration.ofSeconds(10))
               .untilAsserted(() -> assertThat(shipments.findAll()).extracting(Shipment::getOrderId).containsExactly(id));
    }

    @Test
    void aCrashBeforeTheSendLeavesNeitherOrderNorShipment() {
        placeOrderAndCrash(BEFORE_SEND);

        assertThat(orders.count()).isZero();
        assertNoShipmentWithin3Seconds();
    }

    @Test
    void aCrashAfterTheSendLeavesNeitherOrderNorShipment() {
        placeOrderAndCrash(BEFORE_COMMIT);

        assertThat(orders.count()).isZero();
        assertNoShipmentWithin3Seconds();
    }

    /**
     * Act 3, minus the JVM halt: the "crash" fires once, inside the phase-2
     * commit to PostgreSQL. At that moment the order must be in doubt -
     * prepared, but invisible. Atomikos then completes the commit it decided
     * on (its background recovery or its own retry, whichever comes first),
     * and both the order and its shipment appear.
     * <p>
     * The JVM keeps running, so this is not recovery after a restart (that
     * is what the README demo shows).
     */
    @Test
    void aCrashAfterThePrepareIsCompletedByAtomikos() {
        AtomicBoolean crashed = new AtomicBoolean();
        AtomicLong inDoubtAtCrash = new AtomicLong(-1);
        AtomicLong ordersAtCrash = new AtomicLong(-1);
        crashSwitch.crashWith(() -> {
            if (crashed.compareAndSet(false, true)) { // a real crash happens once
                inDoubtAtCrash.set(countInPostgres("SELECT count(*) FROM pg_prepared_xacts"));
                ordersAtCrash.set(countInPostgres("SELECT count(*) FROM purchase_order"));
                throw new IllegalStateException("simulated crash");
            }
        });

        long id = crashSwitch.placeOrderAndCrash(AFTER_PREPARE, () -> orderService.placeOrder("book"));

        assertThat(crashed).as("the crash switch fired in phase 2").isTrue();
        assertThat(inDoubtAtCrash).as("prepared transactions at the crash").hasValue(1);
        assertThat(ordersAtCrash).as("committed orders at the crash").hasValue(0);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(orders.findById(id)).isPresent();
            assertThat(shipments.findAll()).extracting(Shipment::getOrderId).containsExactly(id);
            assertThat(countInPostgres("SELECT count(*) FROM pg_prepared_xacts")).isZero();
        });
    }

    // straight to PostgreSQL, outside Atomikos and the transaction being committed
    private static long countInPostgres(String sql) {
        try (Connection connection = postgres.createConnection("");
                ResultSet result = connection.createStatement().executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

}
