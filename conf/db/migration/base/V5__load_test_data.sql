-- ----------------------------------------------------------------------------
-- CURRENCY_TYPE
-- ----------------------------------------------------------------------------
INSERT INTO org(name,fqon,parent) VALUES ('galacticfog', 'galacticfog',                 (SELECT id from org where name = 'root'));
INSERT INTO org(name,fqon,parent) VALUES ('engineering', 'galacticfog.engineering',     (SELECT id from org where name = 'galacticfog'));
INSERT INTO org(name,fqon,parent) VALUES ('core',        'galacticfog.engineering.core',(SELECT id from org where name = 'engineering'));

INSERT INTO directory(name,description,config,org_id) VALUES
  ('galacticfog-people','directory of galacticfog employees',
   '{
      "directoryType": "native"
    }',
   (SELECT id from org WHERE name = 'galacticfog')
  );

INSERT INTO app(name,service_org_id,org_id) VALUES
  ('gf-system-app', (SELECT id FROM org WHERE name = 'galacticfog'), (SELECT id FROM org WHERE name = 'galacticfog')),
  ('eng-system-app',(SELECT id FROM org WHERE name = 'engineering'),(SELECT id FROM org WHERE name = 'engineering')),
  ('eng-core-system-app',(SELECT id FROM org WHERE name = 'core'),(SELECT id FROM org WHERE name = 'core'));

INSERT INTO account_group(name,dir_id,disabled) VALUES
  ('gf-engineers',(SELECT id from directory where name = 'galacticfog-people'),false),
  ('gf-admins',   (SELECT id from directory WHERE name = 'galacticfog-people'),false);

-- INSERT INTO right_grant(app_id, group_id, grant_name) VALUES (
--   (SELECT id FROM app WHERE name = 'eng-system-app'),
--   (SELECT id FROM account_group WHERE name = 'admins'),
--   'createOrg'
-- ), (
--   (SELECT id FROM app WHERE name = 'eng-system-app'),
--   (SELECT id FROM account_group WHERE name = 'admins'),
--   'deleteOrg'
-- ), (
--   (SELECT id FROM app WHERE name = 'eng-system-app'),
--   (SELECT id FROM account_group WHERE name = 'admins'),
--   'createAccount'
-- ), (
--   (SELECT id FROM app WHERE name = 'eng-core-system-app'),
--   (SELECT id FROM account_group WHERE name = 'admins'),
--   'createOrg'
-- ), (
--   (SELECT id FROM app WHERE name = 'eng-core-system-app'),
--   (SELECT id FROM account_group WHERE name = 'admins'),
--   'deleteOrg'
-- ), (
--   (SELECT id FROM app WHERE name = 'eng-core-system-app'),
--   (SELECT id FROM account_group WHERE name = 'admins'),
--   'createAccount'
-- );

INSERT INTO account_store_mapping(app_id,store_type,account_store_id,default_account_store,default_group_store) VALUES (
  (SELECT id FROM app WHERE name = 'eng-system-app'),
  'DIRECTORY',
  (SELECT id FROM directory WHERE name = 'galacticfog-people'),
  (SELECT id FROM app WHERE name = 'eng-system-app'),
  (SELECT id FROM app WHERE name = 'eng-system-app')
),(
  (SELECT id FROM app WHERE name = 'eng-core-system-app'),
  'DIRECTORY',
  (SELECT id FROM directory WHERE name = 'galacticfog-people'),
  (SELECT id FROM app WHERE name = 'eng-core-system-app'),
  (SELECT id FROM app WHERE name = 'eng-core-system-app')
),(
  (SELECT id FROM app WHERE name = 'gf-system-app'),
  'DIRECTORY',
  (SELECT id FROM directory WHERE name = 'galacticfog-people'),
  (SELECT id FROM app WHERE name = 'gf-system-app'),
  (SELECT id FROM app WHERE name = 'gf-system-app')
);

INSERT INTO account(username,first_name,last_name,email,disabled,secret,hash_method,salt,dir_id) VALUES
  ('anthony','Anthony','Skipper','anthony@galacticfog.com',FALSE, crypt('letmein',gen_salt('bf',10)),'bcrypt','', (SELECT id from directory WHERE name = 'galacticfog-people')),
  ('brad','Brad','Futch','brad@galacticfog.com',FALSE, crypt('letmein',gen_salt('bf',10)),'bcrypt','', (SELECT id from directory WHERE name = 'galacticfog-people')),
  ('sy','Sy','Smythe','sy@galacticfog.com',FALSE, crypt('letmein',gen_salt('bf',10)),'bcrypt','', (SELECT id from directory WHERE name = 'galacticfog-people')),
  ('chris','Chris','Baker','chris@galacticfog.com',FALSE, crypt('letmein',gen_salt('bf',10)),'bcrypt','', (SELECT id from directory WHERE name = 'galacticfog-people'));

INSERT INTO account_x_group(account_id,group_id) VALUES
  ( (SELECT id FROM account WHERE username = 'anthony'), (SELECT id from account_group WHERE name = 'gf-admins') ),
  ( (SELECT id FROM account WHERE username = 'brad'), (SELECT id from account_group WHERE name = 'gf-admins') ),
  ( (SELECT id FROM account WHERE username = 'sy'), (SELECT id from account_group WHERE name = 'gf-admins') ),
  ( (SELECT id FROM account WHERE username = 'chris'), (SELECT id from account_group WHERE name = 'gf-admins') );

INSERT INTO account_x_group(account_id,group_id) VALUES
  ( (SELECT id FROM account WHERE username = 'brad'), (SELECT id from account_group WHERE name = 'gf-engineers') ),
  ( (SELECT id FROM account WHERE username = 'sy'), (SELECT id from account_group WHERE name = 'gf-engineers') ),
  ( (SELECT id FROM account WHERE username = 'chris'), (SELECT id from account_group WHERE name = 'gf-engineers') );
