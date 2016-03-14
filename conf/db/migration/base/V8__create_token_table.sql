-- ----------------------------------------------------------------------------
-- Tokens
-- ----------------------------------------------------------------------------
CREATE TABLE token(
  id            UUID DEFAULT uuid_generate_v4() PRIMARY KEY,
  account_id    UUID NOT NULL REFERENCES account ON DELETE CASCADE,
  issued_at     TIMESTAMP NOT NULL DEFAULT now(),
  expires_at    TIMESTAMP NOT NULL,
  refresh_token UUID REFERENCES token ON DELETE CASCADE
);
