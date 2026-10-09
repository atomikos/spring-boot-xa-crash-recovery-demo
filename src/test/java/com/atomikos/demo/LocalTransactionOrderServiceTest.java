package com.atomikos.demo;

import static com.atomikos.demo.CrashSwitch.CrashPoint.BEFORE_COMMIT;
import static com.atomikos.demo.CrashSwitch.CrashPoint.BEFORE_SEND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * "Why not just {@code @Transactional}?" Without Atomikos it only covers the
 * database: a crash after the send rolls back the order, but the message is
 * already out - and an order that does not exist gets shipped. This is the
 * negative control for {@link AtomicOrderServiceTest}.
 */
@SpringBootTest
@ActiveProfiles("local-tx")
class LocalTransactionOrderServiceTest extends ContainersTestBase {

    @Test
    void aCrashBeforeTheSendIsHarmless() {
        placeOrderAndCrash(BEFORE_SEND);

        assertThat(orders.count()).isZero();
        assertNoShipmentWithin3Seconds();
    }

    @Test
    void aCrashAfterTheSendShipsAnOrderThatDoesNotExist() {
        placeOrderAndCrash(BEFORE_COMMIT);

        assertThat(orders.count()).isZero();
        await().atMost(Duration.ofSeconds(10))
               .untilAsserted(() -> assertThat(shipments.count()).isOne());
    }

}
