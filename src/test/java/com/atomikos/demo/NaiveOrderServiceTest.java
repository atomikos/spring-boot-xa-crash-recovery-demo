package com.atomikos.demo;

import static com.atomikos.demo.CrashSwitch.CrashPoint.BEFORE_SEND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Without a transaction (the dual write): a crash between the insert and the
 * send leaves an order that never ships.
 */
@SpringBootTest
@ActiveProfiles("naive")
class NaiveOrderServiceTest extends ContainersTestBase {

    @Test
    void anOrderIsStoredAndShipped() {
        long id = orderService.placeOrder("book");

        assertThat(orders.findById(id)).isPresent();
        await().atMost(Duration.ofSeconds(10))
               .untilAsserted(() -> assertThat(shipments.findAll()).extracting(Shipment::getOrderId).containsExactly(id));
    }

    @Test
    void aCrashBeforeTheSendLeavesAnOrderThatNeverShips() {
        placeOrderAndCrash(BEFORE_SEND);

        assertThat(orders.count()).isOne();
        assertNoShipmentWithin3Seconds();
    }

}
