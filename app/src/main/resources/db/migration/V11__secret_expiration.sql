-- Axiom 3.x incremental migration 11.
--
-- Optional secret expiration, plus bookkeeping so each expiry event fires once.
alter table secret add column expires_on timestamp(6) with time zone;
alter table secret add column warn_days_before integer;
alter table secret add column expiry_warning_sent_on timestamp(6) with time zone;
alter table secret add column expired_event_sent_on timestamp(6) with time zone;
