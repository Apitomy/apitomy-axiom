-- Axiom 3.x incremental migration 7.
--
-- MCP servers get an explicit transport, HTTP headers, a timeout, an enabled flag and timestamps (#588).
-- Existing rows: transport is inferred (URL => http, otherwise stdio), enabled, timestamps set to now.
alter table mcp_server add column transport varchar(16);
alter table mcp_server add column headers TEXT;
alter table mcp_server add column timeout_ms integer;
alter table mcp_server add column enabled boolean default true not null;
alter table mcp_server add column created_on timestamp(6);
alter table mcp_server add column modified_on timestamp(6);

update mcp_server set transport = case
    when server_url is not null and server_url <> '' then 'http'
    else 'stdio'
end;
update mcp_server set created_on = current_timestamp, modified_on = current_timestamp;

alter table mcp_server alter column transport set not null;
alter table mcp_server alter column created_on set not null;
alter table mcp_server alter column modified_on set not null;
