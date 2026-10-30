-- Axiom 3.x incremental migration 8.
--
-- Action types can explicitly select MCP servers by name (#593), like session templates.
create table action_type_mcp_server (
    action_type_id bigint not null,
    mcp_server_name varchar(255) not null
);

alter table if exists action_type_mcp_server
    add constraint fk_action_type_mcp_server_action_type
    foreign key (action_type_id)
    references action_type on delete cascade;

alter table action_type_mcp_server add unique (action_type_id, mcp_server_name);
