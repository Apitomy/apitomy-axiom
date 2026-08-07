-- Add filters column to event_source table
ALTER TABLE event_source ADD COLUMN filters TEXT;

-- Populate all existing event sources with default filter rules
-- These defaults replace the hardcoded shouldSkipEvent() logic:
--   *[bot] login suffix -> bot filter
--   /* comment body -> slash command filter
UPDATE event_source SET filters =
    '{"include":[],"exclude":['
    || '{"type":"payload","pointer":"/user/login","pattern":"*[bot]"},'
    || '{"type":"payload","pointer":"/comment/user/login","pattern":"*[bot]"},'
    || '{"type":"payload","pointer":"/comment/body","pattern":"/*"}'
    || ']}';
