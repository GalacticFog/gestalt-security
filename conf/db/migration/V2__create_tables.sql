


-- ----------------------------------------------------------------------------
-- ORGS
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS org CASCADE;
CREATE TABLE org(
  org_id character(20) NOT NULL,

  CONSTRAINT pk_org_id PRIMARY KEY(org_id)
);
ALTER TABLE org OWNER TO gestaltdev;


-- ----------------------------------------------------------------------------
-- API_ACCOUNTS
-- ----------------------------------------------------------------------------

DROP TABLE IF EXISTS account_id CASCADE;
CREATE TABLE api_account(
  api_key character(24) NOT NULL,
  api_secret character(40) NOT NULL,
  default_org character(20)  NOT NULL,

  CONSTRAINT fk_default_org FOREIGN KEY (default_org)
    REFERENCES org (org_id) MATCH SIMPLE
    ON DELETE CASCADE,

  CONSTRAINT pk_api_key PRIMARY KEY(api_key)
);
ALTER TABLE api_account OWNER TO gestaltdev;

