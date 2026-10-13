#!/usr/bin/env bash
# Restart the SAME profile WITHOUT resetting -> shows what survived the crash.
set -euo pipefail
cd "$(dirname "$0")"
# Spring Boot 4 needs Java 17+. Honour JAVA_HOME if set; on macOS fall back to java_home.
if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"; export JAVA_HOME
fi
echo "Using: $("${JAVA_HOME:+$JAVA_HOME/bin/}java" -version 2>&1 | head -1)"
p=$(cat .demo-profile 2>/dev/null || echo xa)
echo
echo "────────────────────────────────────────────────────────────"
echo "  Restarting (profile: $p) - no reset, keeping the crashed state"
echo "  Open:  http://localhost:8080   → watch what survived"
echo "────────────────────────────────────────────────────────────"
echo
if [ "$p" = xa ]; then ./mvnw spring-boot:run; else SPRING_PROFILES_ACTIVE="$p" ./mvnw spring-boot:run; fi
