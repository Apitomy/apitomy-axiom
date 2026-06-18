#!/bin/bash

# Serve the mkdocs documentation locally on port 8000.
# Requires: pip install mkdocs mkdocs-material

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if ! command -v mkdocs &> /dev/null; then
    echo "Error: mkdocs is not installed."
    echo "Install it with: pip install mkdocs mkdocs-material"
    exit 1
fi

mkdocs serve --dev-addr 0.0.0.0:8000
