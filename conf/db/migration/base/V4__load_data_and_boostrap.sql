-- ----------------------------------------------------------------------------
-- CURRENCY_TYPE
-- ----------------------------------------------------------------------------
INSERT INTO account_store_type(name, description) VALUES
  ('DIRECTORY', 'A directory of accounts and groups'),
  ('GROUP', 'A group of accounts from an existing directory');

INSERT INTO org(name,fqon) VALUES ('root','root');

INSERT INTO directory(name,description,config,org_id) VALUES
  ('gestalt-user-dir','directory for root organization',
   '{
      "directoryType": "native"
    }',
   (SELECT id from org WHERE name = 'root')
  );

INSERT INTO app(name,service_org_id,org_id) VALUES
  ('root-gestalt-framework',(SELECT id FROM org WHERE name = 'root'),(SELECT id FROM org WHERE name = 'root'));

INSERT INTO account_store_mapping(description,app_id,store_type,account_store_id,default_account_store,default_group_store) VALUES (
  'bootstrapped mapping between root org and full root directory gestalt-user-dir',
  (SELECT id FROM app WHERE name = 'root-gestalt-framework'),
  'DIRECTORY',
  (SELECT id FROM directory WHERE name = 'gestalt-user-dir'),
  (SELECT id FROM app WHERE name = 'root-gestalt-framework'),
  (SELECT id FROM app WHERE name = 'root-gestalt-framework')
);

INSERT INTO account_group(name,disabled,dir_id) VALUES (
  'admins',FALSE,(SELECT id from directory WHERE name = 'gestalt-user-dir')
);

INSERT INTO account(username,first_name,last_name,email,disabled,secret,hash_method,salt,dir_id) VALUES(
    '${root_username}','${root_username}','${root_username}','${root_username}@root',FALSE,
    crypt('${root_password}',gen_salt('bf',10)),'bcrypt','',
    (SELECT id from directory WHERE name = 'gestalt-user-dir')
);

INSERT INTO account_x_group(account_id,group_id) VALUES(
  (SELECT id FROM account WHERE username = '${root_username}'),
  (SELECT id from account_group WHERE name = 'admins')
);

INSERT INTO right_grant(app_id, group_id, grant_name) VALUES (
  (SELECT id FROM app WHERE name = 'root-gestalt-framework'),
  (SELECT id FROM account_group WHERE name = 'admins'),
    'createOrg'
), (
  (SELECT id FROM app WHERE name = 'root-gestalt-framework'),
  (SELECT id FROM account_group WHERE name = 'admins'),
    'deleteOrg'
), (
  (SELECT id FROM app WHERE name = 'root-gestalt-framework'),
  (SELECT id FROM account_group WHERE name = 'admins'),
  'createAccount'
), (
  (SELECT id FROM app WHERE name = 'root-gestalt-framework'),
  (SELECT id FROM account_group WHERE name = 'admins'),
  'deleteAccount'
), (
  (SELECT id FROM app WHERE name = 'root-gestalt-framework'),
  (SELECT id FROM account_group WHERE name = 'admins'),
    'createDirectory'
), (
  (SELECT id FROM app WHERE name = 'root-gestalt-framework'),
  (SELECT id FROM account_group WHERE name = 'admins'),
  'deleteDirectory'
), (
  (SELECT id FROM app WHERE name = 'root-gestalt-framework'),
  (SELECT id FROM account_group WHERE name = 'admins'),
    'readDirectory'
), (
  (SELECT id FROM app WHERE name = 'root-gestalt-framework'),
  (SELECT id FROM account_group WHERE name = 'admins'),
    'createApp'
), (
  (SELECT id FROM app WHERE name = 'root-gestalt-framework'),
  (SELECT id FROM account_group WHERE name = 'admins'),
    'deleteApp'
);
