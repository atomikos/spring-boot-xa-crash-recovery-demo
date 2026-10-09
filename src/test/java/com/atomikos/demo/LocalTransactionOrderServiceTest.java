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
