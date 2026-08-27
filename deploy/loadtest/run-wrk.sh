#!/usr/bin/env bash
# Real network HTTP load test against POST /msg/send_msg, using wrk (https://github.com/wg/wrk).
#
# This deliberately does NOT go through PowerGridSimulationController: that endpoint drives an
# in-process synthetic-load generator with zero network hops per "reading" (see docs), which
# measures the rule engine's compute ceiling, not the HTTP-facing message-push API. This script
# measures the real thing: concurrent HTTP clients -> TCP -> Tomcat -> Spring MVC -> JSON parsing
# -> SendMsgService (template lookup, Redis rate-limit check, MySQL/Kafka enqueue) -> response.
#
# Usage:
#   OPS_USERNAME=operator OPS_PASSWORD=powergrid-demo \
#   ./run-wrk.sh <templateId> [threads] [connections] [duration]
#
# The templateId must already exist and be ACTIVE (status=NORMAL) on a side-effect-free channel
# (SMS with POWERGRID_SMS_TENCENT_ENABLED=false, or Email with no SMTP host configured is NOT
# safe -- it throws per-request). Never point this at a template whose channel is a real,
# rate-limited, or billable provider (Lark webhook, enabled Tencent SMS): wrk will happily fire
# thousands of real deliveries per second.

set -euo pipefail

TEMPLATE_ID="${1:?usage: run-wrk.sh <templateId> [threads] [connections] [duration]}"
THREADS="${2:-4}"
CONNECTIONS="${3:-100}"
DURATION="${4:-30s}"
BASE_URL="${BASE_URL:-http://localhost:8082}"
# Prefix the app is mounted under, when it does not own the server root
# (server.servlet.context-path). Must match BASE_URL's path component, if any.
CONTEXT_PATH="${CONTEXT_PATH:-}"
SEND_PATH="${CONTEXT_PATH}/msg/send_msg"
OPS_USERNAME="${OPS_USERNAME:-operator}"
OPS_PASSWORD="${OPS_PASSWORD:-powergrid-demo}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WRK_BIN="${WRK_BIN:-$HOME/.cache/bitstorm-loadtest/wrk}"

if [[ ! -x "$WRK_BIN" ]]; then
    echo "wrk not found at $WRK_BIN, building from source (github.com/wg/wrk)..." >&2
    BUILD_DIR="$(mktemp -d)"
    git clone --depth 1 https://github.com/wg/wrk.git "$BUILD_DIR"
    make -C "$BUILD_DIR" -j"$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)"
    mkdir -p "$(dirname "$WRK_BIN")"
    cp "$BUILD_DIR/wrk" "$WRK_BIN"
    rm -rf "$BUILD_DIR"
fi

AUTH="$(printf '%s:%s' "$OPS_USERNAME" "$OPS_PASSWORD" | base64)"

# Unique per-run tag so this run's msgIDs never collide with prior runs' rows (msgID is the PK).
# Printed here so you can count exactly what this run persisted:
#   select count(*) from t_msg_queue_low where msg_id like 'wrk-<RUN_ID>-%';
RUN_ID="${LOADTEST_RUN_ID:-$(date +%s)}"

echo "=== wrk -t$THREADS -c$CONNECTIONS -d$DURATION against $BASE_URL$SEND_PATH (templateId=$TEMPLATE_ID) ==="
echo "=== run id: $RUN_ID  (count persisted rows with:  msg_id like 'wrk-$RUN_ID-%') ==="
LOADTEST_TEMPLATE_ID="$TEMPLATE_ID" LOADTEST_RUN_ID="$RUN_ID" LOADTEST_PATH="$SEND_PATH" "$WRK_BIN" \
    -t"$THREADS" -c"$CONNECTIONS" -d"$DURATION" --latency \
    -H "Authorization: Basic $AUTH" \
    -s "$SCRIPT_DIR/send_msg.lua" \
    "$BASE_URL$SEND_PATH"
