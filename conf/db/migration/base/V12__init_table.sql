CREATE TABLE initialization_settings (
  id INT PRIMARY KEY NOT NULL DEFAULT(0) CHECK (id = 0),
  instance_uuid UUID NOT NULL DEFAULT uuid_generate_v4(),
  initialized BOOLEAN NOT NULL,
  root_account  UUID REFERENCES account
);

INSERT INTO initialization_settings (initialized) VALUES (FALSE);
