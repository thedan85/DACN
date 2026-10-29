-- Google's `sub` is the stable account identifier; email can change or be reassigned.
ALTER TABLE users ADD COLUMN google_sub VARCHAR(255);
ALTER TABLE users ADD CONSTRAINT uq_users_google_sub UNIQUE (google_sub);
