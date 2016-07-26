#!/bin/bash

DBUSER=gestaltdev
DBPASS=password
DBNAME=securitytest

set -o errexit
set -o nounset
set -o pipefail

# it's easier to remove it and then start a new one than to try to restart it if it exists with fallback on creation

echo Starting database in docker
db=$(docker run -P -d -e DB_NAME="$DBNAME" -e DB_USER=$DBUSER -e DB_PASS=$DBPASS galacticfog.artifactoryonline.com/centos7postgresql944:latest)

echo Starting ldap in docker
ldap=$(docker run -P -d galacticfog/test-ldap:0.1.0)

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

export TEST_LDAP_URL=ldap://$DOCKERIP:$LDAPPORT

echo ""
echo "Running tests!"
./activator test || true

exit 0
