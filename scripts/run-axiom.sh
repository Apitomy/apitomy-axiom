#!/usr/bin/env bash
#
# Downloads and runs the latest release of Apitomy Axiom.
#
# This script queries the GitHub API for the most recent release,
# downloads the application JAR, and runs it. The JAR is cached
# in ${AXIOM_HOME:-~/.axiom}/releases/ so subsequent runs skip the download.
#
# Prerequisites:
#   - Java 25+
#   - curl
#   - jq (for JSON parsing)
#
# Usage:
#   ./scripts/run-axiom.sh
#

set -euo pipefail

REPO="Apitomy/apitomy-axiom"
CACHE_DIR="${AXIOM_HOME:-${HOME}/.axiom}/releases"

# ── Check prerequisites ───────────────────────────────────────────
if ! command -v java &>/dev/null; then
    echo "ERROR: java not found on PATH"
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')
if [[ "$JAVA_VERSION" -lt 25 ]]; then
    echo "ERROR: Java 25+ required, found Java $JAVA_VERSION"
    exit 1
fi

if ! command -v curl &>/dev/null; then
    echo "ERROR: curl not found on PATH"
    exit 1
fi

if ! command -v jq &>/dev/null; then
    echo "ERROR: jq not found on PATH (install with: sudo dnf install jq)"
    exit 1
fi

# ── Query latest release ──────────────────────────────────────────
echo "Checking for latest Apitomy Axiom release..."

RELEASE_JSON=$(curl -s "https://api.github.com/repos/${REPO}/releases/latest")

TAG=$(echo "$RELEASE_JSON" | jq -r '.tag_name')
VERSION=$(echo "$RELEASE_JSON" | jq -r '.name')

if [[ "$TAG" == "null" || -z "$TAG" ]]; then
    echo "ERROR: Could not determine latest release. API response:"
    echo "$RELEASE_JSON" | head -5
    exit 1
fi

echo "Latest release: ${VERSION} (${TAG})"

# ── Find the JAR asset ───────────────────────────────────────────
JAR_NAME="apitomy-axiom-${VERSION}.jar"
DOWNLOAD_URL=$(echo "$RELEASE_JSON" | jq -r \
    ".assets[] | select(.name == \"${JAR_NAME}\") | .browser_download_url")

if [[ -z "$DOWNLOAD_URL" || "$DOWNLOAD_URL" == "null" ]]; then
    echo "ERROR: Could not find ${JAR_NAME} in release assets."
    echo "Available assets:"
    echo "$RELEASE_JSON" | jq -r '.assets[].name'
    exit 1
fi

# ── Download (with caching) ──────────────────────────────────────
mkdir -p "$CACHE_DIR"
JAR_PATH="${CACHE_DIR}/${JAR_NAME}"

if [[ -f "$JAR_PATH" ]]; then
    echo "Using cached JAR: ${JAR_PATH}"
else
    echo "Downloading ${JAR_NAME}..."
    curl -L -o "$JAR_PATH" "$DOWNLOAD_URL"
    echo "Downloaded to: ${JAR_PATH}"
fi

# ── Run ──────────────────────────────────────────────────────────
echo ""
echo "============================================"
echo "  Starting Apitomy Axiom ${VERSION}"
echo "============================================"
echo ""

exec java -jar "$JAR_PATH" "$@"
