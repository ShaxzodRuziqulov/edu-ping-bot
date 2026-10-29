-- Preserve the Uzbek interface for existing users; new users choose explicitly.
ALTER TABLE app_user ADD COLUMN language varchar(2) CHECK (language IN ('uz','ru'));
UPDATE app_user SET language='uz';
ALTER TABLE app_user ADD COLUMN pending_invite_code varchar(32);
