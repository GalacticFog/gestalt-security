-- ----------------------------------------------------------------------------
-- User accounts
-- ----------------------------------------------------------------------------
CREATE TABLE account(
  id           UUID DEFAULT uuid_generate_v4() PRIMARY KEY,
  dir_id       UUID NOT NULL REFERENCES directory(id) ON DELETE CASCADE,
  username     CHARACTER VARYING(256) NOT NULL,
  email        CHARACTER VARYING(256) NOT NULL,
  phone_number CHARACTER VARYING(15),
  first_name   TEXT NOT NULL,
  last_name    TEXT NOT NULL,
  hash_method  TEXT NOT NULL,
  salt         TEXT NOT NULL,
  secret       TEXT NOT NULL,
  disabled     BOOL NOT NULL,

  UNIQUE (dir_id,username),
  UNIQUE (dir_id,email)
);

-- ----------------------------------------------------------------------------
-- Groups
-- ----------------------------------------------------------------------------
CREATE TABLE account_group(
  id        UUID DEFAULT uuid_generate_v4() PRIMARY KEY,
  dir_id    UUID NOT NULL REFERENCES directory(id) ON DELETE CASCADE,
  name      CHARACTER VARYING(128) NOT NULL,
  disabled  BOOL NOT NULL,

  UNIQUE (dir_id,name)
);

-- ----------------------------------------------------------------------------
-- API Credentials
-- ----------------------------------------------------------------------------
CREATE TABLE api_credential(
  api_key       CHARACTER(24) NOT NULL PRIMARY KEY,
  api_secret    CHARACTER(40) NOT NULL,
  account_id    UUID NOT NULL REFERENCES account ON DELETE CASCADE,
  org_id        UUID NOT NULL REFERENCES org ON DELETE CASCADE,
  disabled      BOOL NOT NULL
);

-- ----------------------------------------------------------------------------
-- Group membership
-- ----------------------------------------------------------------------------
CREATE TABLE account_x_group(
  account_id    UUID NOT NULL REFERENCES account(id) ON DELETE CASCADE,
  group_id      UUID NOT NULL REFERENCES account_group(id) ON DELETE CASCADE,

  PRIMARY KEY(account_id,group_id)
);

-- -- ----------------------------------------------------------------------------
-- Right grant
-- ----------------------------------------------------------------------------
CREATE TABLE right_grant(
  grant_id   UUID DEFAULT uuid_generate_v4() PRIMARY KEY,
  app_id     UUID NOT NULL REFERENCES app(id)  ON DELETE CASCADE,
  account_id UUID REFERENCES account(id)       ON DELETE CASCADE,
  group_id   UUID REFERENCES account_group(id) ON DELETE CASCADE,
  grant_name CHARACTER VARYING(512) NOT NULL,
  grant_value TEXT,

  CHECK (group_id IS NOT NULL OR account_id IS NOT NULL),

  UNIQUE (app_id,grant_name,account_id),
  UNIQUE (app_id,grant_name,group_id)
);
