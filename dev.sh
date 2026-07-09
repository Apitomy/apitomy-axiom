#!/usr/bin/env bash
#
# Starts Apitomy Axiom in development mode.
#
# Launches the Quarkus backend (port 9090) and the Vite UI dev server
# (port 9191) side by side. Both run in the foreground — Ctrl+C stops
# everything.
#
# Prerequisites:
#   - Java 25+
#   - Maven 3.9+
#   - Node.js 20+
#   - npm
#
# First run:
#   The script installs UI dependencies automatically if node_modules
#   is missing. The backend builds all Maven modules before starting.
#
# Usage:
#   ./dev.sh              # start both backend and UI
#   ./dev.sh --skip-ui    # start backend only (Quarkus dev mode)
#   ./dev.sh --persist    # enable persistent storage (H2 file-based DB)
#
# Open http://localhost:9191 in your browser.
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

SKIP_UI=false
PERSIST=false
for arg in "$@"; do
    case "$arg" in
        --skip-ui) SKIP_UI=true ;;
        --persist) PERSIST=true ;;
    esac
done

# ── Prerequisites ─────────────────────────────────────────────────

echo "============================================"
echo "  Apitomy Axiom — Development Mode"
echo "============================================"
echo ""

if ! command -v java &>/dev/null; then
    echo "ERROR: java not found on PATH"
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')
if [[ "$JAVA_VERSION" -lt 25 ]]; then
    echo "ERROR: Java 25+ required, found Java $JAVA_VERSION"
    exit 1
fi

if ! command -v mvn &>/dev/null; then
    echo "ERROR: mvn not found on PATH"
    exit 1
fi

if [[ "$SKIP_UI" == false ]]; then
    if ! command -v node &>/dev/null; then
        echo "ERROR: node not found on PATH"
        exit 1
    fi
    if ! command -v npm &>/dev/null; then
        echo "ERROR: npm not found on PATH"
        exit 1
    fi
fi

echo "Java:   $(java -version 2>&1 | head -1)"
echo "Maven:  $(mvn --version 2>&1 | head -1)"
if [[ "$SKIP_UI" == false ]]; then
    echo "Node:   $(node --version)"
    echo "npm:    $(npm --version)"
fi
echo ""

# ── Build backend ─────────────────────────────────────────────────

echo "Building backend modules..."
mvn clean install -DskipTests -q
echo "Backend build complete."
echo ""

# ── Install UI dependencies ───────────────────────────────────────

if [[ "$SKIP_UI" == false && ! -d "ui/node_modules" ]]; then
    echo "Installing UI dependencies..."
    (cd ui && npm install)
    echo ""
fi

# ── Cleanup on exit ───────────────────────────────────────────────

PIDS=()

cleanup() {
    echo ""
    echo "Shutting down..."
    for pid in "${PIDS[@]}"; do
        kill "$pid" 2>/dev/null || true
    done
    wait 2>/dev/null
}

trap cleanup EXIT INT TERM

# ── Start backend ─────────────────────────────────────────────────

JAVA_ARGS=(-Dquarkus.http.port=9090)
if [[ "$PERSIST" == true ]]; then
    JAVA_ARGS+=(-Dquarkus.profile=persist)
    echo "Starting backend on http://localhost:9090 (persistence enabled) ..."
else
    echo "Starting backend on http://localhost:9090 ..."
fi
java "${JAVA_ARGS[@]}" -jar app/target/quarkus-app/quarkus-run.jar &
PIDS+=($!)

echo -n "Waiting for backend API ..."
until curl -sf http://localhost:9090/api/v1/system/health >/dev/null 2>&1; do
    echo -n "."
    sleep 2
done
echo " ready!"

# ── Start Vite dev server ─────────────────────────────────────────

if [[ "$SKIP_UI" == false ]]; then
    echo "Starting Vite UI on http://localhost:9191 ..."
    (cd ui && npm run dev) &
    PIDS+=($!)

    echo -n "Waiting for UI ..."
    until curl -sf http://localhost:9191/api/v1/system/health >/dev/null 2>&1; do
        echo -n "."
        sleep 2
    done
    echo " ready!"
fi

echo ""
echo "============================================"
if [[ "$SKIP_UI" == false ]]; then
    echo "  UI:      http://localhost:9191"
fi
echo "  API:     http://localhost:9090/api/v1"
echo "  Ctrl+C to stop"
echo "============================================"
echo ""

# Wait for either process to exit
wait -n "${PIDS[@]}" 2>/dev/null || true
