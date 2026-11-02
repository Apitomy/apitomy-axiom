-- Axiom 3.x incremental migration 10.
--
-- Script tools declare their own environment (#617); they no longer inherit the calling agent's.
alter table tool_definition add column environment TEXT;
