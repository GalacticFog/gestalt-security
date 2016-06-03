#!/bin/bash
set -o nounset
set -o errexit
set -o pipefail
# set -o xtrace

security=${1-localhost:9455}

AUTHRESP=$(http POST $security/auth)
rootOrgId=$(echo $AUTHRESP | jq -r '.orgId')
echo Root org is $rootOrgId
adminGrpId=$(echo $AUTHRESP | jq -r '.groups | .[] | select(.name == "admins") | .id')
echo Admin Group is $adminGrpId

# create galacticfog
gfOrgId=$(echo '{"name": "galacticfog", "createDefaultUserGroup": false}' | http $security/orgs/$rootOrgId/orgs | jq -r '.id')
gfAppId=$(http $security/orgs/$gfOrgId/apps |
          jq -r '.[] | select(.isServiceApp == true) | .id')
# create galacticfog.engineering
gfeOrgId=$(echo '{"name": "engineering", "createDefaultUserGroup": false}' | http $security/orgs/$gfOrgId/orgs | jq -r '.id')
gfeAppId=$(http $security/orgs/$gfeOrgId/apps |
          jq -r '.[] | select(.isServiceApp == true) | .id')

gfDirId=$(echo '{"name": "galacticfog-people", "description": "directory of galacticfog employees", "directoryType": "internal"}' |
    http $security/orgs/$gfOrgId/directories |
    jq -r '.id')

gfUserGroupId=$(echo '{"name": "gf-users"}' |
    http $security/directories/$gfDirId/groups |
    jq -r '.id')
gfEngGrpId=$(echo '{"name": "gf-engineers"}' |
    http $security/directories/$gfDirId/groups |
    jq -r '.id')
gfAdminGrpId=$(echo '{"name": "gf-admins"}' |
    http $security/directories/$gfDirId/groups |
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
id=$(echo "$PAYLOAD" | http $security/orgs/$1/accountStores | jq -r '.id')
}

createASM $gfOrgId   $gfAdminGrpId  "mapping between galacticfog and gf-admins"                     false false
createASM $gfOrgId   $gfUserGroupId "mapping between galacticfog and gf-users"                       true false
createASM $gfeOrgId  $gfEngGrpId    "mapping between galacticfog.engineering and gf-engineers"       true false
createASM $gfeOrgId  $gfUserGroupId "mapping between galacticfog.engineering and gf-users"          false false

set +e 
read -r -d '' CHRIS <<EOM
{
  "username": "chris",
  "firstName": "Chris",
  "lastName": "Baker",
  "email": "chris@galacticfog.com",
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
  "groups": ["$gfEngGrpId","$gfAdminGrpId"],
  "credential": {"credentialType": "password", "password": "letmein"}
}
EOM
read -r -d '' VINCE <<EOM
{
  "username": "vince",
  "firstName": "Vince",
  "lastName": "Marco",
  "email": "vince@galacticfog.com",
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
  "groups": ["$gfAdminGrpId"],
  "credential": {"credentialType": "password", "password": "letmein"}
}
EOM
set -e

chrisId=$(echo "$CHRIS"     | http $security/orgs/$gfOrgId/accounts | jq -r '.id')
echo Chris is $chrisId
syId=$(echo "$SY"           | http $security/orgs/$gfOrgId/accounts | jq -r '.id')
echo Sy is $syId
bradId=$(echo "$BRAD"       | http $security/orgs/$gfOrgId/accounts | jq -r '.id')
echo Brad is $bradId
vinceId=$(echo "$VINCE" | http $security/orgs/$gfOrgId/accounts | jq -r '.id')
echo Vince is $vinceId
anthonyId=$(echo "$ANTHONY" | http $security/orgs/$gfOrgId/accounts | jq -r '.id')
echo Anthony is $anthonyId

grantGroupRight() {
  set +e
  read -r -d '' PAYLOAD <<EOM
{
  "grantName": "$3"
} 
EOM
  set -e
  id=$(echo "$PAYLOAD" | http "$security/orgs/$1/groups/$2/rights" | jq -r '.id')
}

grantGroupRight $gfOrgId   $gfAdminGrpId "**"
grantGroupRight $gfeOrgId  $gfEngGrpId   "**"
