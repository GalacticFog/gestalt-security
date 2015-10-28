-- ----------------------------------------------------------------------------
-- DIRECTORY_TYPE
-- ----------------------------------------------------------------------------

DROP TABLE IF EXISTS account_store_type CASCADE;
CREATE TABLE account_store_type(
  id BIGSERIAL PRIMARY KEY,
  name TEXT NOT NULL,
  description TEXT,

  UNIQUE (name)
);
