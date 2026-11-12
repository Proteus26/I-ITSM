-- Session revocation: a token is only valid while its "ver" claim equals users.token_version.
alter table users add column token_version integer not null default 0;

alter table users add column last_login_at timestamptz;
-- The login before last_login_at (shown as "previous sign-in"); null until the second login.
alter table users add column previous_login_at timestamptz;

-- Tokens issued before this migration carry no version (= 0). Deactivated users start at 1 so that such a token
-- cannot come back to life if the account is reactivated.
update users set token_version = 1 where active = false;
