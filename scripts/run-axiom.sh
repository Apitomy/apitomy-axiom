#!/usr/bin/env bash
#
# Downloads and runs a release of Apitomy Axiom.
#
# By default the latest GitHub release is used. Set AXIOM_VERSION (for example AXIOM_VERSION=2.1.2) to pin a
# specific release. The JAR is cached in ${AXIOM_HOME:-~/.axiom}/releases/ so subsequent runs skip the
# download. If GitHub cannot be reached, the newest cached JAR (or the cached pinned JAR) is used instead.
#
# Prerequisites:
#   - Java 25+
#   - curl
#   - jq (for JSON parsing)
#   - Node.js (`node` on the PATH; required by Axiom 3.x)
#
# Usage:
#   ./scripts/run-axiom.sh [java -jar arguments...]
#   AXIOM_VERSION=2.1.2 ./scripts/run-axiom.sh
#
# Environment:
#   AXIOM_VERSION       Release to run (without the leading "v"); default: latest release
#   AXIOM_HOME          Axiom home directory (JAR cache and data); default: ~/.axiom
#   AXIOM_RELEASES_API  GitHub releases API base URL (for testing); default: the project's GitHub API URL
#

set -euo pipefail

REPO="Apitomy/apitomy-axiom"
RELEASES_API="${AXIOM_RELEASES_API:-https://api.github.com/repos/${REPO}/releases}"
UPGRADE_NOTES_URL="https://github.com/${REPO}/releases/tag/v3.0.0"
AXIOM_HOME_DIR="${AXIOM_HOME:-${HOME}/.axiom}"
CACHE_DIR="${AXIOM_HOME_DIR}/releases"
DATA_DIR="${AXIOM_HOME_DIR}/data"
MARKER_FILE="${CACHE_DIR}/last-run-version"
PINNED_VERSION="${AXIOM_VERSION:-}"
PINNED_VERSION="${PINNED_VERSION#v}"

die() {
    echo "ERROR: $*" >&2
    exit 1
}

warn() {
    echo "WARNING: $*" >&2
}

# Prints the major version number of the given version string (e.g. "3" for "3.0.0").
major_of() {
    echo "${1%%.*}"
}

check_prerequisites() {
    command -v java &>/dev/null || die "java not found on PATH (Java 25+ is required)"

    local java_version
    java_version=$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')
    if ! [[ "$java_version" =~ ^[0-9]+$ ]] || [[ "$java_version" -lt 25 ]]; then
        die "Java 25+ required, found: $(java -version 2>&1 | head -1)"
    fi

    command -v curl &>/dev/null || die "curl not found on PATH"
    command -v jq &>/dev/null || die "jq not found on PATH (install with: sudo dnf install jq)"
}

# Checks for Node.js. Axiom 3.x needs it to run MCP tool servers and Node.js actions; for older versions
# a missing node binary only produces a warning.
check_node() {
    local version="$1"
    if command -v node &>/dev/null; then
        return 0
    fi
    local msg="node not found on PATH. Axiom uses Node.js to run the MCP tool servers it gives to AI agents"
    msg+=" and for Node.js actions. Install Node.js (https://nodejs.org/) and make sure 'node' is on the PATH."
    if [[ "$(major_of "$version")" -ge 3 ]]; then
        die "$msg"
    fi
    warn "$msg"
}

# Returns 0 if the existing database at DATA_DIR looks like it was created by Axiom 2.x.
# Heuristic: the database exists and either the last-run marker records a 2.x version, or there is no
# marker but a 2.x JAR is cached.
has_2x_database() {
    [[ -f "${DATA_DIR}/axiom.mv.db" ]] || return 1
    if [[ -f "$MARKER_FILE" ]]; then
        [[ "$(major_of "$(cat "$MARKER_FILE")")" == "2" ]]
        return
    fi
    compgen -G "${CACHE_DIR}/apitomy-axiom-2.*.jar" >/dev/null
}

# Warns before running Axiom 3+ on a database that appears to come from Axiom 2.x. Asks for confirmation
# when stdin is a terminal; otherwise warns and continues (Axiom itself refuses to start on such a
# database).
upgrade_guard() {
    local version="$1"
    # Returns 1 if the warning was shown, so the 2.x marker is kept for the next run.
    [[ "$(major_of "$version")" -ge 3 ]] || return 0
    has_2x_database || return 0

    cat >&2 <<EOF

WARNING: The database at ${DATA_DIR} appears to have been created by Axiom 2.x.
Axiom ${version} cannot use a 2.x database and will refuse to start on it.
Either:
  - back up and then delete ${DATA_DIR} to start fresh with Axiom ${version}, or
  - keep using Axiom 2.x by pinning the version: set AXIOM_VERSION=2.1.2 when running this script
Upgrade notes: ${UPGRADE_NOTES_URL}

EOF
    if [[ -t 0 ]]; then
        local answer
        read -r -p "Continue anyway? [y/N] " answer
        [[ "$answer" =~ ^[Yy]([Ee][Ss])?$ ]] || die "Aborted."
    fi
    return 1
}

# Uses a cached JAR when GitHub cannot be reached. Sets VERSION and JAR_PATH.
use_cached_jar() {
    local reason="$1"
    if [[ -n "$PINNED_VERSION" ]]; then
        JAR_PATH="${CACHE_DIR}/apitomy-axiom-${PINNED_VERSION}.jar"
        [[ -f "$JAR_PATH" ]] || die "${reason}; no cached JAR for version ${PINNED_VERSION} in ${CACHE_DIR}"
        VERSION="$PINNED_VERSION"
    else
        local newest
        newest=$(compgen -G "${CACHE_DIR}/apitomy-axiom-*.jar" | sort -V | tail -1 || true)
        [[ -n "$newest" ]] || die "${reason}; no cached JAR in ${CACHE_DIR}"
        JAR_PATH="$newest"
        VERSION=$(basename "$JAR_PATH" .jar)
        VERSION="${VERSION#apitomy-axiom-}"
    fi
    warn "${reason}; using cached JAR ${JAR_PATH} (may not be the latest release)"
}

# Resolves the release to run and makes sure its JAR is cached. Sets VERSION and JAR_PATH.
resolve_jar() {
    local url
    if [[ -n "$PINNED_VERSION" ]]; then
        url="${RELEASES_API}/tags/v${PINNED_VERSION}"
        echo "Looking up Apitomy Axiom release v${PINNED_VERSION}..."
    else
        url="${RELEASES_API}/latest"
        echo "Checking for latest Apitomy Axiom release..."
    fi

    local release_json
    if ! release_json=$(curl -fsSL -H "Accept: application/vnd.github+json" "$url" 2>&1); then
        use_cached_jar "Could not query GitHub releases (${release_json:-request failed}; possibly offline or rate-limited)"
        return
    fi

    local tag
    tag=$(jq -r '.tag_name // empty' <<<"$release_json" 2>/dev/null || true)
    [[ -n "$tag" ]] || die "Could not determine the release tag from the GitHub API response"
    VERSION="${tag#v}"
    echo "Release: ${VERSION} (${tag})"

    local jar_name="apitomy-axiom-${VERSION}.jar"
    JAR_PATH="${CACHE_DIR}/${jar_name}"
    if [[ -f "$JAR_PATH" ]]; then
        echo "Using cached JAR: ${JAR_PATH}"
        return
    fi

    local download_url
    download_url=$(jq -r --arg n "$jar_name" \
        '.assets[] | select(.name == $n) | .browser_download_url' <<<"$release_json")
    if [[ -z "$download_url" || "$download_url" == "null" ]]; then
        echo "Available assets:" >&2
        jq -r '.assets[].name' <<<"$release_json" >&2
        die "Could not find ${jar_name} in the assets of release ${tag}"
    fi

    echo "Downloading ${jar_name}..."
    PART_PATH="${JAR_PATH}.part"
    trap 'rm -f "${PART_PATH:-}"' EXIT
    curl -fL -o "$PART_PATH" "$download_url" || die "Download of ${download_url} failed"
    mv "$PART_PATH" "$JAR_PATH"
    trap - EXIT
    echo "Downloaded to: ${JAR_PATH}"
}

main() {
    check_prerequisites
    mkdir -p "$CACHE_DIR"
    resolve_jar
    check_node "$VERSION"
    if upgrade_guard "$VERSION"; then
        echo "$VERSION" >"$MARKER_FILE"
    fi

    echo ""
    echo "============================================"
    echo "  Starting Apitomy Axiom ${VERSION}"
    echo "============================================"
    echo ""

    exec java -jar "$JAR_PATH" "$@"
}

# Allow sourcing the functions (e.g. from a test harness) without running the script.
if [[ "${BASH_SOURCE[0]:-$0}" == "$0" ]]; then
    main "$@"
fi
