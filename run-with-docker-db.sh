#!/bin/bash

DBUSER=gestaltdev
DBPASS=***REMOVED***
DBNAME=security
DOCKERDBCONTAINER=gestaltdb

set -o errexit
set -o nounset
set -o pipefail

dockerMachines=$(docker-machine ls | tail -n 1  | awk '{print $1}')
read numMachines <<< $(echo $dockerMachines | wc -l)
case $numMachines in 
0)
    echo "You don't have any docker machines running. Set one up."
    exit 1
    ;;
1)
    DOCKERIP=`docker-machine ip $dockerMachines`
    echo Using docker machine $dockerMachines at $DOCKERIP
    ;;
*)
    echo "You have multiple docker machines. My creator did not endow me with the ability to handle that."
    exit 1
    ;;
esac

# it's easier to remove it and then start a new one than to try to restart it if it exists with fallback on creation

echo Looking for DB in docker
set +o pipefail
isRunning=$(docker inspect $DOCKERDBCONTAINER 2>/dev/null | jq -r '.[0].State.Running')
set -o pipefail
case $isRunning in 
true)
  echo DB container already running
  db=$(docker inspect $DOCKERDBCONTAINER 2>/dev/null | jq -r '.[0].Id')
  ;;
false)
  echo Restarting DB container
  docker start $DOCKERDBCONTAINER
  db=$(docker inspect $DOCKERDBCONTAINER 2>/dev/null | jq -r '.[0].Id')
  ;;
*)
  echo Starting DB container
  db=$(docker run -P -d --name=$DOCKERDBCONTAINER -e DB_NAME=$DBNAME -e DB_USER=$DBUSER -e DB_PASS=$DBPASS galacticfog.artifactoryonline.com/centos7postgresql944:latest)
  ;;
esac

DBPORT=$(docker inspect $db | jq -r '.[0].NetworkSettings.Ports."5432/tcp"[0].HostPort')

echo "
DB running at $DOCKERIP:$DBPORT/$DBNAME
"

sleep 5
echo $DBPASS | docker exec  -i gestaltdb  createdb -h $DOCKERIP -p $DBPORT -U $DBUSER -W $DBNAME || true

cleanup_docker_db() {
while true; do
    read -p "Stop database container?" yn
    case $yn in
        [Yy]* ) 
echo ""
echo Stopping db container
echo Stopped $(docker stop $db)
break;; 
        [Nn]* ) break;;
        * ) echo "Please answer yes or no.";;
    esac
done
echo "
List of running docker containers; make sure I didn't leave anything behind
$(docker ps)
"
}

trap cleanup_docker_db EXIT SIGSTOP SIGTERM

export DATABASE_HOSTNAME=$DOCKERIP
export DATABASE_NAME=$DBNAME
export DATABASE_PORT=$DBPORT
export DATABASE_USERNAME=$DBUSER
export DATABASE_PASSWORD=$DBPASS

# these are the currently defaults, but let's set them explicitly in case they change
export ROOT_USERNAME=root
export ROOT_PASSWORD=letmein

# MIGRATE_DATABASE default is true, and this is already clean db, so we don't need to clean it. 
# export MIGRATE_DATABASE=true
# export CLEAN_ON_MIGRATE=true

echo "
Running gestalt-security on http://localhost:9455
To verify: 
  http -a $ROOT_USERNAME:$ROOT_PASSWORD localhost:9455/sync
or run 
  ./examples/loadTestData.sh

"
./activator run -Dhttp.port=9455

exit 0
