-- ----------------------------------------------------------------------------
-- DIRECTORY_TYPE
-- ----------------------------------------------------------------------------

CREATE TABLE account_store_type(
  id BIGSERIAL PRIMARY KEY,
  name TEXT NOT NULL,
  description TEXT,

  UNIQUE (name)
);
