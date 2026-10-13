#!/usr/bin/env bash
# tx-with-atomikos - @Transactional WITH Atomikos (one XA transaction over DB + broker).
set -euo pipefail
cd "$(dirname "$0")"
# Spring Boot 4 needs Java 17+. Honour JAVA_HOME if set; on macOS fall back to java_home.
if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"; export JAVA_HOME
fi
echo "Using: $("${JAVA_HOME:+$JAVA_HOME/bin/}java" -version 2>&1 | head -1)"
./demo.sh reset
echo xa > .demo-profile
echo
echo "────────────────────────────────────────────────────────────"
echo "  Open:          http://localhost:8080"
echo "  Try pressing:  💣 Crash before the commit   → both roll back (no order, no shipment)"
echo "           or:   💣 Crash after prepare        → in-doubt; recovery completes it on restart"
echo "  Then:          ./restart.sh"
echo "────────────────────────────────────────────────────────────"
echo
./mvnw spring-boot:run
