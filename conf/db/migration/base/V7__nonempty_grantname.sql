ALTER TABLE right_grant ADD CONSTRAINT right_grant_name_nonempty CHECK (grant_name = trim(both ' ' from grant_name) and grant_name <> '');
