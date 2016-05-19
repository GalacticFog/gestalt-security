-- delete them all, they're ephemeral at this version of the schema
DELETE FROM token;
DELETE FROM api_credential;

-- add issued_app_id column
ALTER TABLE token          ADD COLUMN issued_app_id UUID NOT NULL REFERENCES app            ON DELETE CASCADE;
ALTER TABLE token          ADD COLUMN parent_token  UUID          REFERENCES token          ON DELETE CASCADE;
ALTER TABLE token          ADD COLUMN parent_creds  UUID          REFERENCES api_credential ON DELETE CASCADE;

ALTER TABLE api_credential ADD COLUMN issued_app_id UUID NOT NULL REFERENCES app            ON DELETE CASCADE;
ALTER TABLE api_credential ADD COLUMN parent_token  UUID          REFERENCES token          ON DELETE CASCADE;
ALTER TABLE api_credential ADD COLUMN parent_creds  UUID          REFERENCES api_credential ON DELETE CASCADE;

-- make issued_org_id consistent, allow to be not-null
ALTER TABLE token          ALTER COLUMN issued_org_id DROP NOT NULL;
ALTER TABLE api_credential RENAME COLUMN org_id TO issued_org_id;
ALTER TABLE api_credential ALTER COLUMN issued_org_id DROP NOT NULL;
