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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.apache.activemq.artemis.api.jms.management.JMSManagementHelper;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ActiveProfilesResolver;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.Message;
import jakarta.jms.QueueConnection;
import jakarta.jms.QueueRequestor;
import jakarta.jms.QueueSession;
import jakarta.jms.Session;

import com.atomikos.jdbc.AtomikosDataSourceBean;
import com.atomikos.jms.AtomikosConnectionFactoryBean;

/**
 * How much does XA cost? Places orders as fast as possible, from 1, 4 and 16
 * threads, in one mode per run:
 * <ul>
 * <li>{@code naive}: insert and send, each committing on its own</li>
 * <li>{@code local-tx}: {@code @Transactional}, database only</li>
 * <li>{@code xa}: {@code @Transactional} with Atomikos, insert and send in one XA transaction</li>
 * </ul>
 * Not a test, so {@code ./mvnw test} skips it: run it with {@code ./benchmark.sh}
 * (all modes, one JVM and fresh containers each), or one mode with
 * {@code ./mvnw test -Dtest=OrderServiceBenchmark -Dbenchmark.mode=xa}.
 * <p>
 * To compare like with like, every mode gets the same pool sizes, the shipping
 * listener is stopped (only placing orders is measured), and the Atomikos log
 * goes to a local temp directory. Every order is checked afterwards: one row
 * and one message each (counted by the broker), and no in-doubt transactions left.
 * Results are appended to {@code target/benchmark/results.csv}.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        // the same pool size everywhere, larger than the thread count
        "spring.datasource.hikari.maximum-pool-size=" + OrderServiceBenchmark.POOL_SIZE,
        "spring.jta.atomikos.datasource.max-pool-size=" + OrderServiceBenchmark.POOL_SIZE,
        "spring.jta.atomikos.connectionfactory.max-pool-size=" + OrderServiceBenchmark.POOL_SIZE,
        "spring.jms.cache.session-cache-size=" + OrderServiceBenchmark.POOL_SIZE,
        // only placing orders is measured: messages stay on the queue, to be counted
        "spring.jms.listener.auto-startup=false",
        "logging.level.com.atomikos.demo=WARN" })
@ActiveProfiles(resolver = OrderServiceBenchmark.Mode.class)
class OrderServiceBenchmark {

    static final int POOL_SIZE = 32;

    private static final int[] THREADS = ints(System.getProperty("benchmark.threads", "1,4,16"));
    private static final Duration WARMUP = Duration.ofSeconds(Long.getLong("benchmark.warmup", 10));
    private static final Duration MEASURE = Duration.ofSeconds(Long.getLong("benchmark.seconds", 20));
    private static final Path RESULTS = Path.of("target", "benchmark", "results.csv");
    private static final String HEADER = "time,mode,threads,seconds,orders,errors,orders_per_sec,"
            + "p50_ms,p95_ms,p99_ms,max_ms,busy_db_connections,os,java";

    /** {@code -Dbenchmark.mode=naive|local-tx|xa} picks the Spring profile ({@code xa} is the default profile). */
    static class Mode implements ActiveProfilesResolver {

        static final String NAME = System.getProperty("benchmark.mode", "xa");

        @Override
        public String[] resolve(Class<?> testClass) {
            return switch (NAME) {
                case "naive", "local-tx" -> new String[] { NAME };
                case "xa" -> new String[0];
                default -> throw new IllegalArgumentException("benchmark.mode must be naive, local-tx or xa: " + NAME);
            };
        }

    }

    @DynamicPropertySource
    static void connectToContainers(DynamicPropertyRegistry registry) throws IOException {
        // the same containers as the crash tests, but fresh for this JVM
        ContainersTestBase.connectToContainers(registry);
        // Atomikos forces its log to disk on every commit: keep it on a local disk
        // (not a network or Windows drive), so the numbers measure XA, not the filesystem
        Path log = Files.createTempDirectory("atomikos-benchmark-");
        registry.add("atomikos.properties.log-base-dir", log::toString);
        System.out.println("Atomikos log: " + log);
    }

    @Autowired
    OrderService orderService;

    @Autowired
    OrderRepository orders;

    @Autowired
    DataSource dataSource;

    @Autowired
    ConnectionFactory connectionFactory;

    @Test
    void placeOrders() throws Exception {
        System.out.printf("%nmode=%s, DataSource=%s, ConnectionFactory=%s, warm-up %ds, measure %ds%n",
                Mode.NAME, dataSource.getClass().getSimpleName(), connectionFactory.getClass().getSimpleName(),
                WARMUP.toSeconds(), MEASURE.toSeconds());
        // make sure the pool properties took (Atomikos warns about default sizes before they are set)
        if (dataSource instanceof AtomikosDataSourceBean jdbc && connectionFactory instanceof AtomikosConnectionFactoryBean jms) {
            System.out.printf("Atomikos pools: DataSource %d-%d, ConnectionFactory %d-%d%n",
                    jdbc.getMinPoolSize(), jdbc.getMaxPoolSize(), jms.getMinPoolSize(), jms.getMaxPoolSize());
        }
        System.out.printf("%-9s %7s %8s %6s %10s %8s %8s %8s %8s %7s%n",
                "mode", "threads", "orders", "errors", "orders/s", "p50 ms", "p95 ms", "p99 ms", "max ms", "busy db");
        long placed = 0;
        for (int threads : THREADS) {
            placed += run(threads, WARMUP).orders();
            Run run = run(threads, MEASURE);
            placed += run.orders();
            System.out.println(run.line());
            append(run.csv());
            assertThat(run.errors()).as("failed orders in a measured run").isZero();
        }

        // throughput only counts if every order really happened: one row and one message each
        assertThat(orders.count()).as("orders in PostgreSQL").isEqualTo(placed);
        assertThat(messagesOnQueue()).as("messages on the orders queue").isEqualTo(placed);
        assertThat(countInPostgres("SELECT count(*) FROM pg_prepared_xacts")).as("in-doubt transactions").isZero();
        System.out.printf("checked: %d orders, %d messages, no in-doubt transactions%n", placed, placed);
    }

    private Run run(int threads, Duration duration) throws InterruptedException {
        List<long[]> latencies = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger errors = new AtomicInteger();
        AtomicInteger maxDbConnections = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        long[] window = new long[2]; // measured start and end, nanoTime

        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                LongList mine = new LongList();
                try {
                    start.await();
                    long end = window[1];
                    for (long now = System.nanoTime(); now < end; now = System.nanoTime()) {
                        try {
                            orderService.placeOrder("book");
                            mine.add(System.nanoTime() - now);
                        } catch (RuntimeException e) {
                            if (errors.getAndIncrement() == 0) {
                                e.printStackTrace();
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latencies.add(mine.toArray());
                    done.countDown();
                }
            }).start();
        }
        window[0] = System.nanoTime();
        window[1] = window[0] + duration.toNanos();
        start.countDown();
        // how many connections are really in use at once (a pool stuck at 1 would measure waiting, not XA)
        while (!done.await(200, TimeUnit.MILLISECONDS)) {
            maxDbConnections.accumulateAndGet((int) countInPostgres("SELECT count(*) FROM pg_stat_activity"
                    + " WHERE usename = current_user AND state <> 'idle' AND pid <> pg_backend_pid()"), Math::max);
        }
        double seconds = (System.nanoTime() - window[0]) / 1e9;

        long[] all = latencies.stream().flatMapToLong(Arrays::stream).sorted().toArray();
        return new Run(threads, seconds, all, errors.get(), maxDbConnections.get());
    }

    private record Run(int threads, double seconds, long[] sortedLatencies, int errors, int dbConnections) {

        long orders() {
            return sortedLatencies.length;
        }

        double perSecond() {
            return orders() / seconds;
        }

        double percentileMs(double p) {
            if (sortedLatencies.length == 0) {
                return Double.NaN;
            }
            int i = (int) Math.ceil(p / 100 * sortedLatencies.length) - 1;
            return sortedLatencies[Math.max(i, 0)] / 1e6;
        }

        String line() {
            return String.format(Locale.ROOT, "%-9s %7d %8d %6d %10.1f %8.2f %8.2f %8.2f %8.2f %7d",
                    Mode.NAME, threads, orders(), errors, perSecond(),
                    percentileMs(50), percentileMs(95), percentileMs(99), percentileMs(100), dbConnections);
        }

        String csv() {
            return String.format(Locale.ROOT, "%s,%s,%d,%.1f,%d,%d,%.1f,%.3f,%.3f,%.3f,%.3f,%d,%s,%s",
                    Instant.now(), Mode.NAME, threads, seconds, orders(), errors, perSecond(),
                    percentileMs(50), percentileMs(95), percentileMs(99), percentileMs(100), dbConnections,
                    (System.getProperty("os.name") + " " + System.getProperty("os.arch")).replace(',', ' '),
                    System.getProperty("java.version"));
        }

    }

    // asked of the broker itself: a QueueBrowser stops early on a deep (paged) queue
    private static long messagesOnQueue() throws Exception {
        var artemis = ContainersTestBase.artemis;
        try (var factory = new ActiveMQConnectionFactory(artemis.getBrokerUrl(), artemis.getUser(), artemis.getPassword());
                QueueConnection connection = factory.createQueueConnection()) {
            QueueSession session = connection.createQueueSession(false, Session.AUTO_ACKNOWLEDGE);
            connection.start();
            Message request = session.createMessage();
            JMSManagementHelper.putAttribute(request, ResourceNames.QUEUE + "orders", "messageCount");
            Message reply = new QueueRequestor(session, session.createQueue("activemq.management")).request(request);
            assertThat(JMSManagementHelper.hasOperationSucceeded(reply)).as("management request").isTrue();
            return ((Number) JMSManagementHelper.getResult(reply)).longValue();
        }
    }

    // straight to PostgreSQL, outside the pool being measured
    private static long countInPostgres(String sql) {
        try (Connection connection = ContainersTestBase.postgres.createConnection("");
                ResultSet result = connection.createStatement().executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void append(String line) {
        try {
            Files.createDirectories(RESULTS.getParent());
            if (!Files.exists(RESULTS)) {
                Files.writeString(RESULTS, HEADER + "\n");
            }
            Files.writeString(RESULTS, line + "\n", StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int[] ints(String csv) {
        return Arrays.stream(csv.split(",")).map(String::trim).mapToInt(Integer::parseInt).toArray();
    }

    /** A growable long[], so recording a latency doesn't box. */
    private static class LongList {

        private long[] values = new long[1024];
        private int size;

        void add(long value) {
            if (size == values.length) {
                values = Arrays.copyOf(values, size * 2);
            }
            values[size++] = value;
        }

        long[] toArray() {
            return Arrays.copyOf(values, size);
        }

    }

}
