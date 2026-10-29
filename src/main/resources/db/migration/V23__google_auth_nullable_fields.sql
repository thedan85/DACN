-- Google-only accounts have no password and no phone yet.
ALTER TABLE users ALTER COLUMN user_password_hash DROP NOT NULL;
ALTER TABLE customers ALTER COLUMN customer_phone DROP NOT NULL;
