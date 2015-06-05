
-- ----------------------------------------------------------------------------
-- Orgs
-- ----------------------------------------------------------------------------
INSERT INTO org(org_id,org_name) VALUES('DdqZR8D3VztAanAhDESqKNE4','GalacticFog');

-- ----------------------------------------------------------------------------
-- Apps
-- ----------------------------------------------------------------------------
INSERT INTO app(app_id,app_name,org_id) VALUES
  ('MKXR5V1LM0sWu77dDoS3i4hm', 'CallFairyScheduler',       'DdqZR8D3VztAanAhDESqKNE4'),
  ('wNrvvpmglfEX1Pj6WaBM7GyL', 'CallFairyScheduleMonitor', 'DdqZR8D3VztAanAhDESqKNE4'),
  ('TF1HJxn9uMtURqxNSgY76aH4', 'CallFairyNotifier',        'DdqZR8D3VztAanAhDESqKNE4'),
  ('3VAv5fXHdNkT4wGTpz9jEZqR', 'CallFairyCaller',          'DdqZR8D3VztAanAhDESqKNE4'),
  ('6ABRCJb1jebpHo8la3jf0Zhb', 'Launcher',                 'DdqZR8D3VztAanAhDESqKNE4'),
  ('2Kse7zskSHvfWkSnZGg8NWKl', 'Gestalt-DNS',              'DdqZR8D3VztAanAhDESqKNE4'),
  ('xkJgIsKDYgBKri3kywKanX3r', 'Gestalt-Billing',          'DdqZR8D3VztAanAhDESqKNE4');


-- ----------------------------------------------------------------------------
-- User accounts
-- ----------------------------------------------------------------------------
INSERT INTO user_account(account_id,username,email,first_name,last_name,secret,salt,hash_method) VALUES
  ('NNvq1mFQnSB6W8Ti7cZAUsNC','chris',  'chris@galacticfog.com',  'Chris',  'Baker',  'letmein','',''),
  ('TQmkebABtbKNujyMdapgzNkF','sy',     'sy@galacticfog.com',     'Sy',     'Smythe', 'letmein','',''),
  ('isJvay3MQszsp864uIssuq0y','brad',   'brad@galacticfog.com',   'Brad',   'Futch',  'letmein','',''),
  ('IrBID1Msb5u7J0IBtZvn45gc','anthony','anthony@galacticfog.com','Anthony','Skipper','letmein','',''),
  ('D64LHpB7A02UF9GNE8sYrrSc','fred','fred@galacticfog.com','Fred','Dead','letmein','',''),
  ('KlmGJEFJEYamuEzkTtX2eifj','notifierDaemon','','','','letmein','',''),
  ('uRNXpH6wIw1x2WyX53umrPhF','scheduleMonitorDaemon','','','','letmein','',''),
  ('JQsgwAlyemTFNIVMQlr4X8R4','launcherDaemon','','','','letmein','','');

-- ----------------------------------------------------------------------------
-- API Accounts
-- ----------------------------------------------------------------------------
INSERT INTO api_account(api_key,api_secret,account_id,default_org)  VALUES
  ('eJS5uvu0cCLv73K5BRH2yo65','7n67dfimpD72m52d7lNHdwohqi=/=X2/9O74CZri',(SELECT account_id FROM user_account WHERE username = 'chris'),'DdqZR8D3VztAanAhDESqKNE4'),
  ('QnvL1wBvTdvkmyFnPURUdheA','7n67dfimpD72m52d7lNHdwohqi=/=X2/9O74CZri',(SELECT account_id FROM user_account WHERE username = 'brad'),'DdqZR8D3VztAanAhDESqKNE4'),
  ('oJdbcHEqdowysuPrL4fg6WzY','7n67dfimpD72m52d7lNHdwohqi=/=X2/9O74CZri',(SELECT account_id FROM user_account WHERE username = 'brad'),'DdqZR8D3VztAanAhDESqKNE4');


-- ----------------------------------------------------------------------------
-- User groups
-- ----------------------------------------------------------------------------
INSERT INTO user_group(group_id,group_name) VALUES
  ('a9RCjvdRcQ8sbtV5sjs9wlDo', 'gfi'),
  ('Co7LJUYqKuW4N68RSprJUFAV', 'callfairy_admins'),
  ('muesVkFiqECisx0kE3SEugMq', 'callfairy_rest_users'),
  ('TxWGrlXz1WXlyNRsrHItnBsz', 'caller_rest_users'),
  ('kqlv2IQBW1tokAfqPa4HcjTg', 'notifier_rest_users');

-- ----------------------------------------------------------------------------
-- Group membership
-- ----------------------------------------------------------------------------
INSERT INTO account_x_group(group_id,account_id) VALUES
  ((SELECT group_id FROM user_group WHERE group_name = 'gfi'), (SELECT account_id FROM user_account WHERE username = 'brad')),
  ((SELECT group_id FROM user_group WHERE group_name = 'gfi'), (SELECT account_id FROM user_account WHERE username = 'sy')),
  ((SELECT group_id FROM user_group WHERE group_name = 'gfi'), (SELECT account_id FROM user_account WHERE username = 'anthony')),
  ((SELECT group_id FROM user_group WHERE group_name = 'gfi'), (SELECT account_id FROM user_account WHERE username = 'chris')),
  ((SELECT group_id FROM user_group WHERE group_name = 'callfairy_admins'), (SELECT account_id FROM user_account WHERE username = 'anthony')),
  ((SELECT group_id FROM user_group WHERE group_name = 'callfairy_admins'), (SELECT account_id FROM user_account WHERE username = 'chris')),
  ((SELECT group_id FROM user_group WHERE group_name = 'caller_rest_users'), (SELECT account_id FROM user_account WHERE username = 'scheduleMonitorDaemon')),
  ((SELECT group_id FROM user_group WHERE group_name = 'callfairy_rest_users'), (SELECT account_id FROM user_account WHERE username = 'scheduleMonitorDaemon')),
  ((SELECT group_id FROM user_group WHERE group_name = 'callfairy_rest_users'), (SELECT account_id FROM user_account WHERE username = 'notifierDaemon')),
  ((SELECT group_id FROM user_group WHERE group_name = 'notifier_rest_users'), (SELECT account_id FROM user_account WHERE username = 'launcherDaemon'));

-- ----------------------------------------------------------------------------
-- App User store
-- ----------------------------------------------------------------------------
INSERT INTO app_user_store(app_id,group_id) VALUES
  ((SELECT app_id FROM app WHERE app_name = 'CallFairyCaller'),          (SELECT group_id FROM user_group WHERE group_name = 'caller_rest_users')),
  ((SELECT app_id FROM app WHERE app_name = 'CallFairyNotifier'),        (SELECT group_id FROM user_group WHERE group_name = 'notifier_rest_users')),
  ((SELECT app_id FROM app WHERE app_name = 'CallFairyScheduler'),          (SELECT group_id FROM user_group WHERE group_name = 'callfairy_rest_users')),
  ((SELECT app_id FROM app WHERE app_name = 'CallFairyScheduler'),       (SELECT group_id FROM user_group WHERE group_name = 'gfi')),
  ((SELECT app_id FROM app WHERE app_name = 'CallFairyScheduleMonitor'), (SELECT group_id FROM user_group WHERE group_name = 'gfi')),
  ((SELECT app_id FROM app WHERE app_name = 'Launcher'),                 (SELECT group_id FROM user_group WHERE group_name = 'gfi')),
  ((SELECT app_id FROM app WHERE app_name = 'Gestalt-DNS'),                 (SELECT group_id FROM user_group WHERE group_name = 'gfi')),
  ((SELECT app_id FROM app WHERE app_name = 'Gestalt-Billing'),                 (SELECT group_id FROM user_group WHERE group_name = 'gfi'));

-- ----------------------------------------------------------------------------
-- Rights
-- ----------------------------------------------------------------------------
INSERT INTO right_grant(grant_id,grant_name,grant_value,account_id,group_id,app_id) VALUES
  ('WZIpVpyn6ftFEnM1Z241qS2v', 'launcher:full_access',           NULL, NULL,                                  (SELECT group_id from user_group WHERE group_name = 'gfi'), (SELECT app_id from app WHERE app_name = 'Launcher')),
  ('pd6RCTtiyIwtAWgX904Eyl5f', 'callfairy:user_access',          NULL, NULL,                                  (SELECT group_id from user_group WHERE group_name = 'gfi'), (SELECT app_id from app WHERE app_name = 'CallFairyScheduler')),
  ('MSYCBr2RuANnTNfEjkx5IpQF', 'callfairy:call:create',          NULL, (SELECT account_id from user_account WHERE username = 'notifierDaemon'),                     NULL, (SELECT app_id from app WHERE app_name = 'CallFairyScheduler')),
  ('Z0evUArEAtwnQiOVEZT40opI', 'callfairy:call:list',            NULL, (SELECT account_id from user_account WHERE username = 'scheduleMonitorDaemon'),              NULL, (SELECT app_id from app WHERE app_name = 'CallFairyScheduler')),
  ('kuv63mABY7ipv4MjEwYcf2vd', 'call-schedule-monitor:start',    NULL, NULL,                     (SELECT group_id from user_group WHERE group_name = 'callfairy_admins'), (SELECT app_id from app WHERE app_name = 'CallFairyScheduleMonitor')),
  ('Fe7aeZqn9X36AJleQwAyvDSp', 'call-schedule-monitor:stop',     NULL, NULL,                     (SELECT group_id from user_group WHERE group_name = 'callfairy_admins'), (SELECT app_id from app WHERE app_name = 'CallFairyScheduleMonitor')),
  ('rpGHUdDd0T5b46v1lYGtvbla', 'gestalt-notifier:source:create', NULL, (SELECT account_id from user_account WHERE username = 'launcherDaemon'),                     NULL, (SELECT app_id from app WHERE app_name = 'CallFairyNotifier')),
  ('d6GuptkDCaguPIusGBl2x1Gx', 'gestalt-caller:call:create',     NULL, (SELECT account_id from user_account WHERE username = 'scheduleMonitorDaemon'),              NULL, (SELECT app_id from app WHERE app_name = 'CallFairyCaller')),
  ('JfmTZ8Ye7NxxLK1ulV54A3nh', '**',     NULL, (SELECT account_id from user_account WHERE username = 'brad'),              NULL, (SELECT app_id from app WHERE app_name = 'Gestalt-DNS')),
  ('fvEKbhAa4VO26iTDfx5Z1ve8', '**',     NULL, (SELECT account_id from user_account WHERE username = 'brad'),              NULL, (SELECT app_id from app WHERE app_name = 'Gestalt-Billing'));

