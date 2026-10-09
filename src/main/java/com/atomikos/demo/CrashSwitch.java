package com.atomikos.demo;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Kills the JVM at an exact point, so a crash demo never misses its window
 * (a random {@code kill -9} almost always does):
 * <ul>
 * <li>{@code CRASH_BEFORE_SEND=true}: after the database insert, before the JMS send.</li>
 * <li>{@code CRASH_BEFORE_COMMIT=true}: after the JMS send, before the transaction commits.</li>
 * <li>{@code CRASH_AFTER_PREPARE=true}: during commit, after both resources voted
 * yes in phase 1 (prepare) and before PostgreSQL got the phase-2 commit -
 * leaving an in-doubt transaction for Atomikos to recover on restart.</li>
 * </ul>
 * They only fire while an order is being placed, so a restart with a switch
 * still on does not crash again while Atomikos recovers.
 */
@Component
public class CrashSwitch {

    enum CrashPoint {
        BEFORE_SEND("after orders.save(..), before the JMS send"),
        BEFORE_COMMIT("after the JMS send, before the commit"),
        AFTER_PREPARE("PostgreSQL and Artemis both PREPARED, commit decision logged, PostgreSQL NOT committed yet");

        private final String description;

        CrashPoint(String description) {
            this.description = description;
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(CrashSwitch.class);

    private static final ThreadLocal<Boolean> PLACING_AN_ORDER = ThreadLocal.withInitial(() -> false);

    private final Set<CrashPoint> armed = EnumSet.noneOf(CrashPoint.class);

    private Runnable crash = () -> Runtime.getRuntime().halt(1);

    public CrashSwitch(@Value("${CRASH_BEFORE_SEND:false}") boolean beforeSend,
            @Value("${CRASH_BEFORE_COMMIT:false}") boolean beforeCommit,
            @Value("${CRASH_AFTER_PREPARE:false}") boolean afterPrepare) {
        if (beforeSend) {
            armed.add(CrashPoint.BEFORE_SEND);
        }
        if (beforeCommit) {
            armed.add(CrashPoint.BEFORE_COMMIT);
        }
        if (afterPrepare) {
            armed.add(CrashPoint.AFTER_PREPARE);
        }
        armed.forEach(point -> LOGGER.warn("💣 armed: CRASH_{} - the next order kills the JVM: {}", point, point.description));
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

    long whilePlacingAnOrder(LongSupplier placeOrder) {
        PLACING_AN_ORDER.set(true);
        try {
            return placeOrder.getAsLong();
        } finally {
            PLACING_AN_ORDER.remove();
        }
    }

    /** For tests: arm only this crash point, and do something other than halting the JVM. */
    void armForTest(CrashPoint point, Runnable crash) {
        armed.clear();
        armed.add(point);
        this.crash = crash;
    }

    private void crashIfArmed(CrashPoint point) {
        if (armed.contains(point) && PLACING_AN_ORDER.get()) {
            LOGGER.error("💥 CRASH! {} - halting the JVM now (no shutdown hooks, no cleanup)", point.description);
            System.out.flush();
            crash.run();
        }
    }

}
