-- Rename the widgets column to tabs
ALTER TABLE dashboard RENAME COLUMN widgets TO tabs;

-- Migrate existing data: wrap each widgets JSON array in a single "Default" tab
UPDATE dashboard
SET tabs = CONCAT('[{"id":"00000000-0000-0000-0000-000000000000","name":"Default","widgets":', COALESCE(tabs, '[]'), '}]');
