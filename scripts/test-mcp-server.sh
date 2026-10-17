#!/usr/bin/env bash
#
# Tests the Axiom MCP server by creating a sample tools JSON file and
# invoking the server over the MCP stdio protocol to list and call a tool.
#
# Usage:
#   ./scripts/test-mcp-server.sh          # list tools + call hello_world
#   ./scripts/test-mcp-server.sh --list   # list tools only
#   ./scripts/test-mcp-server.sh --call   # list tools + call hello_world
#

set -euo pipefail

MCP_SERVER_DIR="${AXIOM_HOME:-${HOME}/.axiom}/mcp-server"
SERVER_JS="${MCP_SERVER_DIR}/server.js"
TOOLS_FILE=$(mktemp /tmp/axiom-test-tools-XXXXXX.json)

# Verify the MCP server is installed
if [[ ! -f "${SERVER_JS}" ]]; then
    echo "ERROR: MCP server not found at ${SERVER_JS}"
    echo "Run the Axiom application first to install it, or run:"
    echo "  cd ${MCP_SERVER_DIR} && npm install"
    exit 1
fi

# Create a sample tools JSON file
cat > "${TOOLS_FILE}" << 'TOOLS'
[
  {
    "name": "hello_world",
    "description": "Says hello to someone",
    "scriptTemplate": "echo Hello, {{name}}!",
    "parameters": [
      { "name": "name", "type": "string", "description": "Who to greet", "required": true }
    ]
  },
  {
    "name": "list_files",
    "description": "Lists files in a directory",
    "scriptTemplate": "ls -la {{path}}",
    "parameters": [
      { "name": "path", "type": "string", "description": "Directory path", "required": true }
    ]
  },
  {
    "name": "repo_summary",
    "description": "Produces a summary of a git repository",
    "scriptTemplate": "cd {{path}}\necho \"=== Repository: $(basename $(pwd)) ===\"\necho \"\"\necho \"--- Branches ---\"\ngit branch -a 2>/dev/null || echo \"Not a git repo\"\necho \"\"\necho \"--- Recent Commits ---\"\ngit log --oneline -5 2>/dev/null || echo \"No commits\"\necho \"\"\necho \"--- File Count ---\"\nfind . -type f -not -path './.git/*' | wc -l",
    "parameters": [
      { "name": "path", "type": "string", "description": "Path to the repository", "required": true }
    ]
  }
]
TOOLS

echo "Tools JSON: ${TOOLS_FILE}"
echo ""

MODE="${1:---call}"

# Build the JSON-RPC messages
INIT='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test-script","version":"1.0"}}}'
INITIALIZED='{"jsonrpc":"2.0","method":"notifications/initialized"}'
LIST='{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
CALL='{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"hello_world","arguments":{"name":"Axiom"}}}'

if [[ "${MODE}" == "--list" ]]; then
    MESSAGES="${INIT}\n${INITIALIZED}\n${LIST}"
else
    MESSAGES="${INIT}\n${INITIALIZED}\n${LIST}\n${CALL}"
fi

# Run the server and pipe messages, separating stdout (responses) from stderr (logs)
RESPONSES=$(echo -e "${MESSAGES}" | node "${SERVER_JS}" "${TOOLS_FILE}" 2>/dev/null)

# Parse and display results
echo "=== MCP Server Responses ==="
echo ""

echo "${RESPONSES}" | while IFS= read -r line; do
    # Extract the id to determine which response this is
    ID=$(echo "${line}" | python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null || echo "")

    case "${ID}" in
        1)
            echo "--- Initialize ---"
            echo "${line}" | python3 -m json.tool 2>/dev/null || echo "${line}"
            echo ""
            ;;
        2)
            echo "--- Tools List ---"
            echo "${line}" | python3 -m json.tool 2>/dev/null || echo "${line}"
            echo ""
            ;;
        3)
            echo "--- Tool Call Result (hello_world name=Axiom) ---"
            echo "${line}" | python3 -m json.tool 2>/dev/null || echo "${line}"
            echo ""
            ;;
        *)
            echo "--- Unknown Response ---"
            echo "${line}"
            echo ""
            ;;
    esac
done

# Cleanup
rm -f "${TOOLS_FILE}"
