#!/usr/bin/env bash
# Copyright 2026 Atomikos
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Helper for the demo. The app itself runs with ./mvnw spring-boot:run (see README).
set -euo pipefail
cd "$(dirname "$0")"

psql() { docker compose exec -T postgres psql -U shop -d shop -q "$@"; }

case "${1:-}" in
  order)
    curl -s -X POST "localhost:8080/orders?product=${2:-book}" || { echo "(no answer - did the app just crash?)"; exit 1; }
    ;;
  crash)
    curl -s -X POST "localhost:8080/orders?product=book&crash=${2:?usage: $0 crash before-send|before-commit|after-prepare}" \
      || echo "(no answer: the app crashed)"
    ;;
  status)
    psql -c "SELECT coalesce(o.id, s.order_id) AS \"order\", o.product,
                    CASE WHEN o.id IS NULL THEN '👻 shipped, but the order does not exist'
                         WHEN s.order_id IS NULL THEN '❌ NOT shipped'
                         ELSE '✅ shipped' END AS shipment
             FROM purchase_order o FULL JOIN shipment s ON s.order_id = o.id ORDER BY 1" \
         -c "SELECT gid AS \"in-doubt (prepared) transactions\", prepared FROM pg_prepared_xacts"
    ;;
  reset)
    docker compose down
    rm -rf transaction-logs
    ;;
  *)
    echo "usage: $0 order [product] | crash before-send|before-commit|after-prepare | status | reset"
    exit 1
    ;;
esac
