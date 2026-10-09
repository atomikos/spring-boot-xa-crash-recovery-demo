package com.atomikos.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Places orders: each order is a row in PostgreSQL plus a message on an
 * Artemis queue, which the shipping side turns into a shipment.
 * <p>
 * Run it without Atomikos (profile {@code naive}) and with Atomikos (default),
 * then crash it on purpose with {@link CrashSwitch} and see what survives.
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

}
