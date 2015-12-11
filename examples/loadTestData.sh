#!/bin/bash
set -o nounset
set -o errexit
set -o pipefail
set -o xtrace

username=${ROOT_USERNAME-root}
password=${ROOT_PASSWORD-letmein}
security=${1-localhost:9455}
auth="--auth $username:$password"

AUTHRESP=$(http $auth POST $security/auth)
rootOrgId=$(echo $AUTHRESP | jq -r '.orgId')
adminGrpId=$(echo $AUTHRESP | jq -r '.groups | .[] | select(.name == "admins") | .id')

# create galacticfog
gfOrgId=$(echo '{"name": "galacticfog", "createDefaultUserGroup": false}' | http $auth $security/orgs/$rootOrgId | jq -r '.id')
gfAppId=$(http $auth $security/orgs/$gfOrgId/apps |
          jq -r '.[] | select(.isServiceApp == true) | .id')
# create galacticfog.engineering
gfeOrgId=$(echo '{"name": "engineering", "createDefaultUserGroup": false}' | http $auth $security/orgs/$gfOrgId | jq -r '.id')
gfeAppId=$(http $auth $security/orgs/$gfeOrgId/apps |
          jq -r '.[] | select(.isServiceApp == true) | .id')
# create galacticfog.engineering.core
gfecOrgId=$(echo '{"name": "core", "createDefaultUserGroup": false}' | http $auth $security/orgs/$gfeOrgId | jq -r '.id')
gfecAppId=$(http $auth $security/orgs/$gfecOrgId/apps |
          jq -r '.[] | select(.isServiceApp == true) | .id')

gfDirId=$(echo '{"name": "galacticfog-people", "description": "directory of galacticfog employees", "config": {"directoryType": "internal"}}' |
    http $auth $security/orgs/$gfOrgId/directories |
    jq -r '.id')

gfUserGroupId=$(echo '{"name": "gf-users"}' |
    http $auth $security/directories/$gfDirId/groups |
    jq -r '.id')
gfEngGrpId=$(echo '{"name": "gf-engineers"}' |
    http $auth $security/directories/$gfDirId/groups |
    jq -r '.id')
gfAdminGrpId=$(echo '{"name": "gf-admins"}' |
    http $auth $security/directories/$gfDirId/groups |
    jq -r '.id')

createASM() {
set +e
read -r -d '' PAYLOAD <<EOM
{
  "name": "",
  "storeType": "GROUP",
  "accountStoreId": "$2",
  "description": "$3",
  "isDefaultAccountStore": $4,
  "isDefaultGroupStore": $5
} 
EOM
set -e
id=$(echo "$PAYLOAD" | http $auth $security/orgs/$1/accountStores | jq -r '.id')
}

createASM $gfOrgId   $gfAdminGrpId  "mapping between galacticfog and gf-admins"                     false false
createASM $gfOrgId   $gfUserGroupId "mapping between galacticfog and gf-users"                       true false
createASM $gfeOrgId  $gfEngGrpId    "mapping between galacticfog.engineering and gf-engineers"       true false
createASM $gfeOrgId  $gfUserGroupId "mapping between galacticfog.engineering and gf-users"          false false
createASM $gfecOrgId $gfEngGrpId    "mapping between galacticfog.engineering.core and gf-engineers"  true false
createASM $gfecOrgId $gfUserGroupId "mapping between galacticfog.engineering.core and gf-users"     false false

set +e 
read -r -d '' CHRIS <<EOM
{
  "username": "chris",
  "firstName": "Chris",
  "lastName": "Baker",
  "email": "chris@galacticfog.com",
  "phoneNumber": "",
  "groups": ["$gfEngGrpId","$gfAdminGrpId"],
  "credential": {"credentialType": "password", "password": "letmein"}
}
EOM
read -r -d '' SY <<EOM
{
  "username": "sy",
  "firstName": "Sy",
  "lastName": "Smythe",
  "email": "sy@galacticfog.com",
  "phoneNumber": "",
  "groups": ["$gfEngGrpId","$gfAdminGrpId"],
  "credential": {"credentialType": "password", "password": "letmein"}
}
EOM
read -r -d '' BRAD <<EOM
{
  "username": "brad",
  "firstName": "Brad",
  "lastName": "Futch",
  "email": "brad@galacticfog.com",
  "phoneNumber": "",
  "groups": ["$gfEngGrpId","$gfAdminGrpId"],
  "credential": {"credentialType": "password", "password": "letmein"}
}
EOM
read -r -d '' ANTHONY <<EOM
{
  "username": "anthony",
  "firstName": "Anthony",
  "lastName": "Skipper",
  "email": "anthony@galacticfog.com",
  "phoneNumber": "",
  "groups": ["$gfAdminGrpId"],
  "credential": {"credentialType": "password", "password": "letmein"}
}
EOM
set -e

chrisId=$(echo "$CHRIS"     | http $auth $security/orgs/$gfOrgId/accounts | jq -r '.id')
syId=$(echo "$SY"           | http $auth $security/orgs/$gfOrgId/accounts | jq -r '.id')
bradId=$(echo "$BRAD"       | http $auth $security/orgs/$gfOrgId/accounts | jq -r '.id')
anthonyId=$(echo "$ANTHONY" | http $auth $security/orgs/$gfOrgId/accounts | jq -r '.id')

grantGroupRight() {
  set +e
  read -r -d '' PAYLOAD <<EOM
{
  "grantName": "$3"
} 
EOM
  set -e
  id=$(echo "$PAYLOAD" | http $auth "$security/orgs/$1/groups/$2/rights" | jq -r '.id')
}

grantGroupRight $gfOrgId   $gfAdminGrpId "**"
grantGroupRight $gfeOrgId  $gfEngGrpId   "**"
grantGroupRight $gfecOrgId $gfEngGrpId   "**"
