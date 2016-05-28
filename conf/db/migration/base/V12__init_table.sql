CREATE TABLE initialization_settings (
  id INT PRIMARY KEY NOT NULL DEFAULT(1) CHECK (id = 1),
  instance_uuid UUID NOT NULL DEFAULT uuid_generate_v4(),
  initialized BOOLEAN NOT NULL,
  root_account  UUID REFERENCES account
);

INSERT INTO initialization_settings (initialized) VALUES (FALSE);
