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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.activemq.ArtemisContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Starts PostgreSQL and Artemis in containers and points the application at
 * them. Needs Docker; without Docker the tests are skipped instead of failing.
 * <p>
 * The crash switch throws instead of halting the JVM (a halt would kill the
 * test runner too).
 * <p>
 * Each test class closes its Spring context when done: otherwise the cached
 * contexts of the other profiles keep their {@link ShippingListener} running on
 * the same queue, and could consume this class's messages outside its
 * transaction setup.
 */
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class ContainersTestBase {

    // started once and shared by all test classes (whose Spring contexts are cached),
    // stopped by Testcontainers when the JVM exits

    // XA (two-phase commit) needs prepared transactions, disabled by default
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withCommand("postgres", "-c", "max_prepared_transactions=100");

    static ArtemisContainer artemis = new ArtemisContainer("apache/activemq-artemis:2.40.0-alpine");

    @DynamicPropertySource
    static void connectToContainers(DynamicPropertyRegistry registry) {
        if (!postgres.isRunning()) {
            postgres.start();
            artemis.start();
        }
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.artemis.broker-url", artemis::getBrokerUrl);
        registry.add("spring.artemis.user", artemis::getUser);
        registry.add("spring.artemis.password", artemis::getPassword);
        registry.add("atomikos.properties.log-base-dir", () -> "target/transaction-logs");
    }

    @Autowired
    OrderService orderService;

    @Autowired
    OrderRepository orders;

    @Autowired
    ShipmentRepository shipments;

    @Autowired
    CrashSwitch crashSwitch;

    @BeforeEach
    void cleanUp() {
        shipments.deleteAll();
        orders.deleteAll();
    }

    void placeOrderAndCrash(CrashSwitch.CrashPoint point) {
        crashSwitch.crashWith(() -> {
            throw new IllegalStateException("simulated crash");
        });
        // only the simulated crash may stop the order: any other failure fails the test
        assertThatThrownBy(() -> crashSwitch.placeOrderAndCrash(point, () -> orderService.placeOrder("book")))
                .hasMessage("simulated crash");
    }

    void assertNoShipmentWithin3Seconds() {
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5))
               .untilAsserted(() -> assertThat(shipments.count()).isZero());
    }

}
