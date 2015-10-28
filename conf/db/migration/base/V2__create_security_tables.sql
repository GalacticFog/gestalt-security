-- ----------------------------------------------------------------------------
-- ORGS
-- ----------------------------------------------------------------------------
CREATE TABLE org(
  id     UUID DEFAULT uuid_generate_v1mc() PRIMARY KEY,
  name TEXT NOT NULL,
  fqon TEXT NOT NULL,
  parent UUID REFERENCES org(id) ON DELETE CASCADE,

  UNIQUE (parent,name),
  UNIQUE (fqon)
);

-- ----------------------------------------------------------------------------
-- DIRECTORIES
-- ----------------------------------------------------------------------------
CREATE TABLE directory(
  id UUID DEFAULT uuid_generate_v1mc() PRIMARY KEY,
  name TEXT NOT NULL,
  description TEXT,
  org_id UUID NOT NULL REFERENCES org ON DELETE CASCADE,
  config JSON NOT NULL,

  UNIQUE (name,org_id)
);

-- ----------------------------------------------------------------------------
-- APPS
-- ----------------------------------------------------------------------------
CREATE TABLE app(
  id UUID DEFAULT uuid_generate_v1mc() PRIMARY KEY,
  name TEXT NOT NULL,
  org_id UUID NOT NULL REFERENCES org ON DELETE CASCADE,
  service_org_id UUID REFERENCES org ON DELETE CASCADE,

  UNIQUE (name,org_id),
  UNIQUE (service_org_id)
);

-- ----------------------------------------------------------------------------
-- ACCOUNT STORE MAPPINGS
-- account store mapping between an account store and an application
-- the account store can be either a group or a directory
-- hence the account_store_id below is non-null, but without REFERENCES
-- ----------------------------------------------------------------------------
CREATE TABLE account_store_mapping(
  id UUID DEFAULT uuid_generate_v1mc() PRIMARY KEY,
  app_id     UUID NOT NULL REFERENCES app(id) ON DELETE CASCADE,
  store_type TEXT NOT NULL REFERENCES account_store_type(name) ON DELETE RESTRICT,
  account_store_id UUID NOT NULL,
  -- only one account_store_mapping can be the default_account_store for a particular app
  default_account_store UUID REFERENCES app(id) UNIQUE,
  -- only one account_store_mapping can be the default_group_store for a particular app
  default_group_store   UUID REFERENCES app(id) UNIQUE,

  -- can only have one account_store_mapping from a particular app to a particular account_store
  UNIQUE (app_id,store_type,account_store_id)
);

