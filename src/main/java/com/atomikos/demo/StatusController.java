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

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What {@code ./demo.sh status} shows, as JSON for the demo page
 * ({@code index.html}): orders, shipments and in-doubt transactions, straight
 * from PostgreSQL. Demo plumbing only.
 */
@RestController
public class StatusController {

    private static final String ORDERS = """
            SELECT coalesce(o.id, s.order_id) AS "order", o.product,
                   CASE WHEN o.id IS NULL THEN '👻 shipped, but the order does not exist'
                        WHEN s.order_id IS NULL THEN '❌ NOT shipped'
                        ELSE '✅ shipped' END AS shipment
            FROM purchase_order o FULL JOIN shipment s ON s.order_id = o.id ORDER BY 1""";

    private static final String IN_DOUBT = "SELECT gid, prepared FROM pg_prepared_xacts";

    private final JdbcTemplate jdbc;
    private final String mode;

    public StatusController(JdbcTemplate jdbc, Environment environment) {
        this.jdbc = jdbc;
        List<String> profiles = Arrays.asList(environment.getActiveProfiles());
        this.mode = profiles.contains("naive") ? "no XA: the classic dual write"
                : profiles.contains("local-tx") ? "@Transactional without XA (database only)"
                : "Atomikos: one XA transaction";
    }

    @GetMapping("/status")
    @Transactional(readOnly = true)
    public Map<String, Object> status() {
        return Map.of("mode", mode,
                      "orders", jdbc.queryForList(ORDERS),
                      "inDoubt", jdbc.queryForList(IN_DOUBT));
    }

}
