#!/usr/bin/env bash
# =============================================================================
# run_experiment.sh
# Orchestrates all WealthWise cache consistency benchmark runs.
#
# Usage:
#   bash scripts/run_experiment.sh --all          # Full 45-run experiment
#   bash scripts/run_experiment.sh --smoke        # Single quick smoke test
#   bash scripts/run_experiment.sh --strategy cache-aside --ttl static-30 --sigma 0.30
#
# Prerequisites:
#   - Java 21, Maven installed
#   - Docker Compose stack running: docker compose up -d
#   - JMeter 5.6+ on PATH as 'jmeter'
#
# Output:
#   results/staleness.csv        — E_stale measurements from StalenessMetricsFilter
#   results/jmeter/<run>.csv     — JMeter throughput/latency summaries
#   results/redis/<run>.txt      — Redis INFO stats snapshots
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
RESULTS_DIR="$ROOT_DIR/results"
JAR="$ROOT_DIR/target/wealthwise-1.0.0-SNAPSHOT.jar"
JMETER_PLAN="$ROOT_DIR/jmeter/wealthwise-benchmark.jmx"

# Experiment dimensions
STRATEGIES=("cache-aside" "write-through" "swr")
TTL_POLICIES=("static-5" "static-30" "static-60" "dynamic" "event")
# Sigma maps to volatility regimes: low=0.05, medium=0.30, high=0.80
SIGMAS=("0.05" "0.30" "0.80")
SIGMA_LABELS=("low" "medium" "high")

USERS=200           # concurrent users (fixed for core experiment)
WARMUP_SEC=60       # JVM warm-up duration
MEASURE_SEC=300     # measurement window (5 minutes)
RAMP_SEC=30

# Parse CLI arguments
MODE="all"
SINGLE_STRATEGY=""
SINGLE_TTL=""
SINGLE_SIGMA=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --all)    MODE="all" ; shift ;;
        --smoke)  MODE="smoke" ; shift ;;
        --strategy) SINGLE_STRATEGY="$2" ; MODE="single" ; shift 2 ;;
        --ttl)      SINGLE_TTL="$2"      ; shift 2 ;;
        --sigma)    SINGLE_SIGMA="$2"    ; shift 2 ;;
        *) echo "Unknown argument: $1" ; exit 1 ;;
    esac
done

# ---- Helpers ----------------------------------------------------------------

log() { echo "[$(date '+%H:%M:%S')] $*"; }

build_jar() {
    log "Building WealthWise JAR..."
    cd "$ROOT_DIR"
    mvn -q clean package -DskipTests
}

start_app() {
    local strategy="$1" ttl="$2" sigma="$3"
    log "Starting WealthWise: strategy=$strategy ttl=$ttl sigma=$sigma"
    APP_PID_FILE="$RESULTS_DIR/.app.pid"

    java -jar "$JAR" \
        --spring.profiles.active="$strategy" \
        --wealthwise.caching.ttl-policy="$ttl" \
        --wealthwise.simulator.sigma="$sigma" \
        --wealthwise.staleness.csv-output-path="$RESULTS_DIR/staleness.csv" \
        > "$RESULTS_DIR/app.log" 2>&1 &

    echo $! > "$APP_PID_FILE"
    log "Waiting for app to start (PID=$(cat "$APP_PID_FILE"))..."
    sleep 15
    # Confirm health
    curl -sf http://localhost:8080/actuator/health | grep -q '"status":"UP"' || {
        log "ERROR: App did not start. Check $RESULTS_DIR/app.log"; exit 1;
    }
    log "App running."
}

stop_app() {
    APP_PID_FILE="$RESULTS_DIR/.app.pid"
    if [[ -f "$APP_PID_FILE" ]]; then
        local pid
        pid=$(cat "$APP_PID_FILE")
        kill "$pid" 2>/dev/null || true
        rm -f "$APP_PID_FILE"
        log "App stopped (PID=$pid)."
        sleep 3
    fi
}

flush_redis() {
    log "Flushing Redis..."
    docker exec wealthwise-redis redis-cli FLUSHALL > /dev/null
}

snapshot_redis_stats() {
    local run_label="$1"
    local out="$RESULTS_DIR/redis/${run_label}.txt"
    mkdir -p "$RESULTS_DIR/redis"
    docker exec wealthwise-redis redis-cli INFO stats > "$out"
    docker exec wealthwise-redis redis-cli INFO keyspace >> "$out"
    log "Redis stats saved to $out"
}

run_jmeter() {
    local strategy="$1" ttl="$2" sigma_label="$3" run_label="$4"
    local out_csv="$RESULTS_DIR/jmeter/${run_label}.csv"
    mkdir -p "$RESULTS_DIR/jmeter"

    log "Running JMeter: $run_label (users=$USERS, measure=${MEASURE_SEC}s)"

    # Warm-up phase (not recorded)
    jmeter -n \
        -t "$JMETER_PLAN" \
        -Jusers="$USERS" \
        -Jduration="$WARMUP_SEC" \
        -Jrampup="$RAMP_SEC" \
        -Jhost=localhost \
        -Jport=8080 \
        -l /dev/null \
        -j "$RESULTS_DIR/jmeter/${run_label}_warmup.log" \
        > /dev/null 2>&1

    # Measurement phase
    jmeter -n \
        -t "$JMETER_PLAN" \
        -Jusers="$USERS" \
        -Jduration="$MEASURE_SEC" \
        -Jrampup="$RAMP_SEC" \
        -Jhost=localhost \
        -Jport=8080 \
        -l "$out_csv" \
        -j "$RESULTS_DIR/jmeter/${run_label}_measure.log" \
        > /dev/null 2>&1

    log "JMeter done. Results: $out_csv"
}

run_single() {
    local strategy="$1" ttl="$2" sigma="$3" sigma_label="$4"
    local run_label="${strategy}__${ttl}__${sigma_label}"

    log "======== RUN: $run_label ========"
    flush_redis
    start_app "$strategy" "$ttl" "$sigma"
    run_jmeter "$strategy" "$ttl" "$sigma_label" "$run_label"
    snapshot_redis_stats "$run_label"
    stop_app
    log "======== DONE: $run_label ========"
}

# ---- Main -------------------------------------------------------------------

mkdir -p "$RESULTS_DIR"
build_jar

case "$MODE" in
    smoke)
        run_single "cache-aside" "static-30" "0.30" "medium"
        ;;
    single)
        sigma_label="medium"
        for i in "${!SIGMAS[@]}"; do
            if [[ "${SIGMAS[$i]}" == "$SINGLE_SIGMA" ]]; then
                sigma_label="${SIGMA_LABELS[$i]}"; fi
        done
        run_single "$SINGLE_STRATEGY" "$SINGLE_TTL" "$SINGLE_SIGMA" "$sigma_label"
        ;;
    all)
        TOTAL=$(( ${#STRATEGIES[@]} * ${#TTL_POLICIES[@]} * ${#SIGMAS[@]} ))
        count=0
        for strategy in "${STRATEGIES[@]}"; do
            for ttl in "${TTL_POLICIES[@]}"; do
                for i in "${!SIGMAS[@]}"; do
                    sigma="${SIGMAS[$i]}"
                    sigma_label="${SIGMA_LABELS[$i]}"
                    count=$(( count + 1 ))
                    log "Progress: $count / $TOTAL"
                    run_single "$strategy" "$ttl" "$sigma" "$sigma_label"
                done
            done
        done
        log "All $TOTAL runs complete. Results in $RESULTS_DIR"
        ;;
esac
