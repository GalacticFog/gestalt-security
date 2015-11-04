#!/bin/bash
set -o nounset
set -o errexit
set -o pipefail
set -o xtrace

username=${ROOT_USERNAME-admin}
password=${ROOT_PASSWORD-letmein}
security=${1-localhost:9455}
auth="--auth $username:$password"

AUTHRESP=$(http $auth POST $security/auth)
rootOrgId=$(echo $AUTHRESP | jq -r '.orgId')
adminGrpId=$(echo $AUTHRESP | jq -r '.groups | .[] | select(.name == "admins") | .id')

# create galacticfog
gfOrgId=$(echo '{"orgName": "galacticfog"}' | http $auth $security/orgs/$rootOrgId | jq -r '.id')
gfAppId=$(http $auth $security/orgs/$gfOrgId/apps |
          jq -r '.[] | select(.isServiceApp == true) | .id')
# create galacticfog.engineering
gfeOrgId=$(echo '{"orgName": "engineering"}' | http $auth $security/orgs/$gfOrgId | jq -r '.id')
gfeAppId=$(http $auth $security/orgs/$gfeOrgId/apps |
          jq -r '.[] | select(.isServiceApp == true) | .id')
# create galacticfog.engineering.core
gfecOrgId=$(echo '{"orgName": "core"}' | http $auth $security/orgs/$gfeOrgId | jq -r '.id')
gfecAppId=$(http $auth $security/orgs/$gfecOrgId/apps |
          jq -r '.[] | select(.isServiceApp == true) | .id')

gfDirId=$(echo '{"name": "galacticfog-people", "description": "directory of galacticfog employees", "config": {"directoryType": "native"}}' |
    http $auth $security/orgs/$gfOrgId/directories |
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
  "appId": "$2",
  "storeType": "GROUP",
  "accountStoreId": "$3",
  "description": "$4",
  "isDefaultAccountStore": $5,
  "isDefaultGroupStore": $5
} 
EOM
set -e
echo "$PAYLOAD" | http $auth $security/orgs/$1/accountStores
}

createASM $gfOrgId $gfAppId $gfAdminGrpId "mapping between galacticfog and gf-admins" true
createASM $gfeOrgId $gfeAppId $gfEngGrpId "mapping between galacticfog.engineering and gf-engineers" true
createASM $gfecOrgId $gfecAppId $gfEngGrpId "mapping between galacticfog.engineering.core and gf-engineers" true

#{
#  "username": "chris",
#  "firstName": "Chris",
#  "lastName": "Baker",
#  "email": "chris@galacticfog.com",
#  "phoneNumber": "",
#  "groups": ["$gfEngGrpId","$gfAdminGrpId"],
#  "credential": {"credentialType": "password", "password": "letmein"}
#}
