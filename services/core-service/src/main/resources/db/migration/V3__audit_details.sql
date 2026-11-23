-- Audit events can say what changed, e.g. "manager: Mark Manager -> Alice Admin".
-- (Adding a column doesn't touch existing rows, so the append-only triggers don't fire.)
ALTER TABLE audit_events ADD COLUMN details VARCHAR(500);
