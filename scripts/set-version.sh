#!/usr/bin/env bash
# Sets the Apitomy Axiom version everywhere it is recorded:
#   - all Maven modules (pom.xml, including the ui profile modules)
#   - the OpenAPI contract (info.version)
#   - the UI package (ui/package.json and ui/package-lock.json)
#   - the bundled MCP server packages and their MCP server versions
#
# Usage: scripts/set-version.sh <version>      e.g. scripts/set-version.sh 3.0.0
#        scripts/set-version.sh 3.0.1-SNAPSHOT
set -euo pipefail

if [[ $# -ne 1 || -z "$1" ]]; then
    echo "Usage: $0 <version>" >&2
    exit 1
fi
VERSION="$1"
if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]]; then
    echo "Not a semantic version: $VERSION" >&2
    exit 1
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "Setting version $VERSION"

mvn -q versions:set -DnewVersion="$VERSION" -DgenerateBackupPoms=false -Pui

# JSON files: change only the version values, keeping each file's formatting.
node - "$VERSION" <<'EOF'
const fs = require("fs");
const version = process.argv[2];

/** Replaces the first match of a pattern in a file, failing loudly when it is not found. */
function replaceIn(file, pattern, replacement) {
    const text = fs.readFileSync(file, "utf8");
    if (!pattern.test(text)) {
        throw new Error(`Version not found in ${file}: ${pattern}`);
    }
    fs.writeFileSync(file, text.replace(pattern, replacement));
}

// OpenAPI: the "version" member of the top-level "info" object.
replaceIn("common/api/src/main/resources/openapi.json",
    /("info": \{[\s\S]*?\n    "version": ")[^"]*(")/, `$1${version}$2`);

// UI package and its lock file (root package entries only).
replaceIn("ui/package.json", /^(    "version": ")[^"]*(")/m, `$1${version}$2`);
const lock = "ui/package-lock.json";
replaceIn(lock, /^(    "version": ")[^"]*(")/m, `$1${version}$2`);
replaceIn(lock, /("packages": \{\s*"": \{[^}]*?"version": ")[^"]*(")/, `$1${version}$2`);

// Bundled MCP servers: package.json version and the version each server reports over MCP.
for (const dir of fs.readdirSync("app/src/main/resources/templates")) {
    const base = `app/src/main/resources/templates/${dir}`;
    if (!fs.existsSync(`${base}/package.json`)) {
        continue;
    }
    replaceIn(`${base}/package.json`, /^(    "version": ")[^"]*(")/m, `$1${version}$2`);
    for (const file of fs.readdirSync(base).filter((f) => f.endsWith(".js"))) {
        const path = `${base}/${file}`;
        const text = fs.readFileSync(path, "utf8");
        const updated = text.replace(/(new Server\(\{ name: "[^"]+", version: ")[^"]*(")/g, `$1${version}$2`);
        if (updated !== text) {
            fs.writeFileSync(path, updated);
        }
    }
}
EOF

echo "Version set to $VERSION"
