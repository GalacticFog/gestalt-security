CREATE TABLE token_type (
  type text PRIMARY KEY
);

INSERT INTO token_type(type) VALUES
  ('REFRESH'),
  ('ACCESS');

ALTER TABLE token ADD COLUMN issued_org_id UUID NOT NULL REFERENCES org ON DELETE CASCADE;

ALTER TABLE token ADD COLUMN token_type TEXT NOT NULL REFERENCES token_type ON DELETE RESTRICT;