


-- ----------------------------------------------------------------------------
-- ORGS
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS org CASCADE;
CREATE TABLE org(
  org_id character(24) NOT NULL,
  org_name character VARYING(512) NOT NULL,

  CONSTRAINT pk_org_id PRIMARY KEY(org_id),
  CONSTRAINT unique_org_name UNIQUE (org_name)
);
ALTER TABLE org OWNER TO gestaltdev;


-- ----------------------------------------------------------------------------
-- APPS
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS app CASCADE;
CREATE TABLE app(
  app_id character(24) NOT NULL,
  app_name character VARYING(256) NOT NULL,
  org_id character(24) NOT NULL,

  CONSTRAINT pk_app_id PRIMARY KEY(app_id),
  CONSTRAINT fk_org_id FOREIGN KEY (org_id)
    REFERENCES org (org_id) MATCH SIMPLE
    ON DELETE CASCADE
);
ALTER TABLE app OWNER TO gestaltdev;

-- ----------------------------------------------------------------------------
-- User accounts
-- Long term, this isn't actually part of our database
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS user_account CASCADE;
CREATE TABLE user_account(
  account_id  CHARACTER(24) NOT NULL,
  username    CHARACTER VARYING(256) NOT NULL,
  email       CHARACTER VARYING(256) NOT NULL,
  first_name  TEXT NOT NULL,
  last_name   TEXT NOT NULL,
  secret      TEXT NOT NULL,
  salt        TEXT NOT NULL,
  hash_method TEXT NOT NULL,

  CONSTRAINT pk_username PRIMARY KEY(account_id),
  CONSTRAINT unique_username UNIQUE(username)
--   CONSTRAINT unique_email    UNIQUE(email)
--   CONSTRAINT proper_email CHECK (check_email(email))
);
ALTER TABLE user_account OWNER TO gestaltdev;

-- ----------------------------------------------------------------------------
-- API_ACCOUNTS
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS api_account CASCADE;
CREATE TABLE api_account(
  api_key character(24) NOT NULL,
  api_secret character(40) NOT NULL,
  default_org character(24)  NOT NULL,
  account_id character(24) NOT NULL,

  CONSTRAINT fk_default_org FOREIGN KEY (default_org)
    REFERENCES org (org_id) MATCH SIMPLE
    ON DELETE CASCADE,

  CONSTRAINT fk_account_id FOREIGN KEY (account_id)
    REFERENCES user_account(account_id) MATCH SIMPLE
    ON DELETE CASCADE,

  CONSTRAINT pk_api_key PRIMARY KEY(api_key)
);
ALTER TABLE api_account OWNER TO gestaltdev;

-- ----------------------------------------------------------------------------
-- Groups
-- Long term, this isn't actually part of our database either
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS user_group CASCADE;
CREATE TABLE user_group(
  group_id CHARACTER(24) NOT NULL,
  group_name CHARACTER VARYING(256) NOT NULL,

  CONSTRAINT pk_group_id PRIMARY KEY(group_id),
  CONSTRAINT unique_group_name UNIQUE(group_name)
);
ALTER TABLE user_group OWNER TO gestaltdev;

-- ----------------------------------------------------------------------------
-- Group membership
-- Long term, this isn't actually part of our database either
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS account_x_group CASCADE;
CREATE TABLE account_x_group(
  group_id CHARACTER(24) NOT NULL,
  account_id CHARACTER(24) NOT NULL,

  CONSTRAINT pk_user_x_group PRIMARY KEY(account_id,group_id),
  CONSTRAINT fk_user_id FOREIGN KEY(account_id)
    REFERENCES user_account(account_id) MATCH SIMPLE
    ON DELETE CASCADE,
  CONSTRAINT fk_group_id FOREIGN KEY(group_id)
    REFERENCES user_group(group_id) MATCH SIMPLE
    ON DELETE CASCADE
);
ALTER TABLE account_x_group OWNER TO gestaltdev;

-- ----------------------------------------------------------------------------
-- App group assignments
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS app_x_group CASCADE;
CREATE TABLE app_x_group(
  app_id CHARACTER(24) NOT NULL,
  group_id CHARACTER(24) NOT NULL,

  CONSTRAINT pk_app_x_group PRIMARY KEY(app_id,group_id),
  CONSTRAINT fk_group_id FOREIGN KEY(group_id)
    REFERENCES user_group(group_id) MATCH SIMPLE
    ON DELETE CASCADE
);
ALTER TABLE app_x_group OWNER TO gestaltdev;

-- ----------------------------------------------------------------------------
-- App user assignments
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS app_x_account CASCADE;
CREATE TABLE app_x_account(
  app_id CHARACTER(24) NOT NULL,
  account_id CHARACTER(24) NOT NULL,

  CONSTRAINT pk_app_x_account PRIMARY KEY(app_id,account_id),
  CONSTRAINT fk_account_id FOREIGN KEY(account_id)
    REFERENCES user_account(account_id) MATCH SIMPLE
    ON DELETE CASCADE
);
ALTER TABLE app_x_account OWNER TO gestaltdev;

-- ----------------------------------------------------------------------------
-- Right grant
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS right_grant CASCADE;
CREATE TABLE right_grant(
  grant_id CHARACTER(24) NOT NULL,
  app_id CHARACTER(24) NOT NULL,
  account_id CHARACTER(24),
  group_id CHARACTER(24),
  grant_name CHARACTER VARYING(512) NOT NULL,
  grant_value TEXT,

  CHECK (group_id IS NOT NULL OR account_id IS NOT NULL),

  CONSTRAINT pk_grant_id PRIMARY KEY(grant_id),
  CONSTRAINT fk_app_id FOREIGN KEY(app_id)
    REFERENCES app(app_id) MATCH SIMPLE
    ON DELETE CASCADE,
  CONSTRAINT fk_user_id FOREIGN KEY(account_id)
    REFERENCES user_account(account_id) MATCH SIMPLE
    ON DELETE CASCADE,
  CONSTRAINT fk_group_id FOREIGN KEY(group_id)
    REFERENCES user_group(group_id) MATCH SIMPLE
    ON DELETE CASCADE
);
ALTER TABLE right_grant OWNER TO gestaltdev;
