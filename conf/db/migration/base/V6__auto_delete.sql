ALTER TABLE account ALTER COLUMN email DROP NOT NULL;

ALTER TABLE account_group ADD COLUMN parent_org UUID REFERENCES org ON DELETE CASCADE;

UPDATE org
  SET name=lower(name), fqon=lower(fqon)
  WHERE name != lower(name) or fqon != lower(fqon);

ALTER TABLE org ADD CHECK (name = lower(name) and fqon = lower(fqon));
