-- Idempotency keys are now stored as '<userId>:<key>' so one user's key can never match
-- another user's notification. userId (64) + ':' + key (64) needs up to 129 characters.
alter table notification alter column idempotency_key type varchar(160);
