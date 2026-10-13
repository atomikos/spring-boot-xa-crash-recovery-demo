#!/usr/bin/env bash
# naive - dual write, NO transaction. Crash loses the message; a restart never heals it.
set -euo pipefail
cd "$(dirname "$0")"
# Spring Boot 4 needs Java 17+. Honour JAVA_HOME if set; on macOS fall back to java_home.
if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"; export JAVA_HOME
fi
echo "Using: $("${JAVA_HOME:+$JAVA_HOME/bin/}java" -version 2>&1 | head -1)"
./demo.sh reset
echo naive > .demo-profile
echo
echo "────────────────────────────────────────────────────────────"
echo "  Open:          http://localhost:8080"
echo "  Try pressing:  💣 Crash before the send"
echo "  Then:          ./restart.sh   → order stays ❌ NOT shipped"
echo "────────────────────────────────────────────────────────────"
echo
SPRING_PROFILES_ACTIVE=naive ./mvnw spring-boot:run
