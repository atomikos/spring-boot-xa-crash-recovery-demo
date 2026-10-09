package com.atomikos.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code curl -X POST localhost:8080/orders?product=book}
 */
@RestController
public class OrderController {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrderController.class);

    private final OrderService orderService;
    private final CrashSwitch crashSwitch;

    public OrderController(OrderService orderService, CrashSwitch crashSwitch) {
        this.orderService = orderService;
        this.crashSwitch = crashSwitch;
    }

    @PostMapping("/orders")
    public String placeOrder(@RequestParam(defaultValue = "book") String product) {
        long id = crashSwitch.whilePlacingAnOrder(() -> orderService.placeOrder(product));
        LOGGER.info("🛒 placed order #{} ({})", id, product);
        return "placed order #" + id + "\n";
    }

}
