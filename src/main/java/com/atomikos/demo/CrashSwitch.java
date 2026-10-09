package com.atomikos.demo;

import java.io.IOException;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Kills the JVM at an exact point while placing one order, so a crash demo
 * never misses its window (a random {@code kill -9} almost always does).
 * <p>
 * {@code ./demo.sh crash before-send} sends {@code POST /orders?crash=before-send}:
 * that one request crashes {@link CrashPoint#BEFORE_SEND before the send}, and
 * so on. Other requests, and Atomikos recovering after a restart, never crash.
 */
@Component
public class CrashSwitch extends OncePerRequestFilter {

    public enum CrashPoint {
        BEFORE_SEND("after orders.save(..), before the JMS send"),
        BEFORE_COMMIT("after the JMS send, before the commit"),
        AFTER_PREPARE("PostgreSQL and Artemis both PREPARED, commit decision logged, PostgreSQL NOT committed yet");

        private final String description;

        CrashPoint(String description) {
            this.description = description;
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(CrashSwitch.class);

    // the crash point of the order being placed on this thread, if any
    private static final ThreadLocal<CrashPoint> ARMED = new ThreadLocal<>();

    private Runnable crash = () -> Runtime.getRuntime().halt(1);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String crash = request.getParameter("crash"); // e.g. after-prepare
        if (crash != null) {
            arm(CrashPoint.valueOf(crash.toUpperCase().replace('-', '_')));
        }
        try {
            chain.doFilter(request, response);
        } finally {
            ARMED.remove();
        }
    }

    public void beforeSend() {
        crashIfArmed(CrashPoint.BEFORE_SEND);
    }

    public void beforeCommit() {
        crashIfArmed(CrashPoint.BEFORE_COMMIT);
    }

    void beforePhase2Commit() {
        crashIfArmed(CrashPoint.AFTER_PREPARE);
    }

    /** For tests: place an order that crashes at this point. */
    long placeOrderAndCrash(CrashPoint point, LongSupplier placeOrder) {
        arm(point);
        try {
            return placeOrder.getAsLong();
        } finally {
            ARMED.remove();
        }
    }

    /** For tests: do something other than halting the JVM, which would kill the test runner too. */
    void crashWith(Runnable crash) {
        this.crash = crash;
    }

    private static void arm(CrashPoint point) {
        LOGGER.warn("💣 this order will crash: {}", point.description);
        ARMED.set(point);
    }

    private void crashIfArmed(CrashPoint point) {
        if (ARMED.get() == point) {
            LOGGER.error("💥 CRASH! {} - halting the JVM now (no shutdown hooks, no cleanup)", point.description);
            System.out.flush();
            crash.run();
        }
    }

}
