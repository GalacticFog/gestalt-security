CREATE TABLE directory_type(
  id BIGSERIAL PRIMARY KEY,
  name TEXT NOT NULL,
  description TEXT,

  UNIQUE (name)
);

INSERT INTO directory_type(name, description) VALUES
  ('INTERNAL', 'A local directory of accounts and groups');

INSERT INTO directory_type(name, description) VALUES
  ('LDAP', 'An LDAP directory of accounts');

ALTER TABLE directory ADD COLUMN directory_type TEXT NOT NULL REFERENCES directory_type(name) ON DELETE RESTRICT DEFAULT 'INTERNAL';

UPDATE directory SET directory_type = 'INTERNAL';

ALTER TABLE directory ALTER COLUMN directory_Type DROP DEFAULT;
