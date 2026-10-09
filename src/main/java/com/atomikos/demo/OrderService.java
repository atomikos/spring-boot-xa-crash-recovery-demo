package com.atomikos.demo;

/**
 * Places an order: a row in PostgreSQL plus a message on the orders queue.
 */
public interface OrderService {

    long placeOrder(String product);

}
