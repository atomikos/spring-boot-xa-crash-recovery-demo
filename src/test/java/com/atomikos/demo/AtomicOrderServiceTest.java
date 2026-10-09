package com.atomikos.demo;

import static com.atomikos.demo.CrashSwitch.CrashPoint.BEFORE_COMMIT;
import static com.atomikos.demo.CrashSwitch.CrashPoint.BEFORE_SEND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * With Atomikos: the order row and the order message are one XA transaction,
 * so a crash leaves neither.
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

}
