#!/usr/bin/env bash
#
# Builds the entire Apitomy Axiom project and runs all tests, including
# integration tests that require the Claude Code CLI to be installed.
#
# Prerequisites:
#   - Java 25+
#   - Maven 3.9+
#   - claude CLI on PATH
#   - ANTHROPIC_API_KEY set in the environment
#
# Usage:
#   ./build-all.sh
#

set -euo pipefail

echo "============================================"
echo "  Apitomy Axiom — Full Build + All Tests"
echo "============================================"
echo ""

# Check prerequisites
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

if ! command -v claude &>/dev/null; then
    echo "WARNING: claude CLI not found — Claude Code integration tests will fail"
fi

if [[ -z "${ANTHROPIC_API_KEY:-}" ]]; then
    echo "WARNING: ANTHROPIC_API_KEY not set — Claude Code integration tests will fail"
fi

echo "Java:   $(java -version 2>&1 | head -1)"
echo "Maven:  $(mvn --version 2>&1 | head -1)"
echo "Claude: $(command -v claude 2>/dev/null || echo 'not found')"
echo ""

# Run the build with all tests enabled
mvn clean package "$@"
