-- Axiom 3.x incremental migration 9.
--
-- Tools discovered by the last successful MCP server connection test (#595), used for allowed-tools suggestions.
alter table mcp_server add column discovered_tools TEXT;
alter table mcp_server add column tools_discovered_on timestamp(6);
