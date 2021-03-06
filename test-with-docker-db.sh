#!/bin/bash

DBIMAGE=galacticfog/postgres_repl:release-1.4.0

DBUSER=gestaltdev
DBPASS=password
DBNAME=gestalt-security

set -o errexit
set -o nounset
set -o pipefail

# it's easier to remove it and then start a new one than to try to restart it if it exists with fallback on creation

echo Starting database in docker
docker pull $DBIMAGE
db=$(docker run -P -d -e POSTGRES_USER=$DBUSER -e POSTGRES_PASSWORD=$DBPASS $DBIMAGE)

echo Starting ldap in docker
docker pull galacticfog/test-ldap:latest
ldap=$(docker run -P -d galacticfog/test-ldap:latest)

DBPORT=$(docker inspect $db | jq -r '.[0].NetworkSettings.Ports."5432/tcp"[0].HostPort')
LDAPPORT=$(docker inspect $ldap | jq -r '.[0].NetworkSettings.Ports."389/tcp"[0].HostPort')
DOCKERIP=$(docker inspect $db | jq -r '.[0].NetworkSettings.Ports."5432/tcp"[0].HostIp')
if [ "$DOCKERIP" == "0.0.0.0" ]; then 
  DOCKERIP="localhost"
fi

echo "
DB running at $DOCKERIP:$DBPORT/$DBNAME
LDAP running at $DOCKERIP:$LDAPPORT
"

cleanup_docker_db() {
echo ""
echo Stopping db container
echo Stopped $(docker stop $db)
echo Removing db container
echo Removed $(docker rm $db)
echo Stopping ldap container
echo Stopped $(docker stop $ldap)
echo Removing ldap container
echo Removed $(docker rm $ldap)

echo ""
echo "List of running docker containers; make sure I didn't leave anything behind"
docker ps
}

trap cleanup_docker_db EXIT SIGSTOP SIGTERM

export DATABASE_HOSTNAME=$DOCKERIP
export DATABASE_NAME=$DBNAME
export DATABASE_PORT=$DBPORT
export DATABASE_USERNAME=$DBUSER
export DATABASE_PASSWORD=$DBPASS

export TEST_LDAP_USER="cn=admin,dc=example,dc=com"
export TEST_LDAP_PASS="password"
export TEST_LDAP_URL="ldap://$DOCKERIP:$LDAPPORT"

echo ""
echo "Running tests!"
if [ $# -eq 0 ]; then 
  AUDIT_LOG=testlogs/audit.log ACCESS_LOG=testlogs/access.log sbt test || true
else 
  sbt "$*"  || true
fi


exit 0
