-- Rename SDK tool prefix from mcp__axiom-tools__axiom_ to mcp__axiom-sdk__axiom_
-- after splitting the combined MCP server into separate SDK and Tools servers.
-- Safe because no script tool name starts with "axiom_".

UPDATE toolset SET tools = REPLACE(tools, 'mcp__axiom-tools__axiom_', 'mcp__axiom-sdk__axiom_')
  WHERE tools LIKE '%mcp__axiom-tools__axiom_%';

UPDATE action_type SET allowed_tools = REPLACE(allowed_tools, 'mcp__axiom-tools__axiom_', 'mcp__axiom-sdk__axiom_')
  WHERE allowed_tools LIKE '%mcp__axiom-tools__axiom_%';

UPDATE report_definition SET allowed_tools = REPLACE(allowed_tools, 'mcp__axiom-tools__axiom_', 'mcp__axiom-sdk__axiom_')
  WHERE allowed_tools LIKE '%mcp__axiom-tools__axiom_%';
