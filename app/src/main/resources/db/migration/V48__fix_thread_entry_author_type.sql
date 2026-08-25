-- Fix thread_entry rows that still have the old 'actor' author type.
-- V45 renamed actor -> agent but missed this column.
UPDATE thread_entry SET author_type = 'agent' WHERE author_type = 'actor';
