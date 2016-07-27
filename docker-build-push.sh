#!/bin/bash 

export IMG=galacticfog.artifactoryonline.com/gestalt-security

set -e
# set -x

if [ -z ${GITLAB_CI+x} ]; then
    git fetch
    read numChanges <<< $(git status --short | wc -l)
    branch=$(git symbolic-ref --short HEAD)
    
    if [ $numChanges -gt 0 ]; then 
      echo "You are dirty. You probably shouldn't be running this script."
      exit 1       
    fi
    if [ $branch != "master" ]; then 
      echo "Uh-oh. You appear not to be on the master branch. You probably shouldn't be running this script yet."
      exit 1
    fi
    if [ `git rev-parse origin/master` != `git rev-parse HEAD` ]; then 
      echo "Your master branch does not appear to agree with origin/master. You probably shouldn't be running this script yet."
      exit 1
    fi
    
    export SHA=$(git rev-parse --short=8 HEAD)
    export VER=$(grep "^version" build.sbt | sed 's/.*:=[ ]*//' | sed 's/"//g')
    export TAG=$VER-$SHA
else 
    export SHA=${CI_BUILD_REF:0:8}
    export VER=$(grep "^version" build.sbt | sed 's/.*:=[ ]*//' | sed 's/"//g')
    export DEFTAG=$VER-$SHA
    export TAG=${CI_BUILD_TAG-$DEFTAG}
fi

echo "
Build info:
  SHA: $SHA
  Version: $VER
  Ref: ${CI_BUILD_REF_NAME-none}
  Tag: ${CI_BUILD_TAG-none}
  Docker image: $IMG:$TAG
"

echo "Creating build image..."
sbt docker:stage
cd target/docker/stage
docker build -t $IMG:$TAG .
echo "Pushing new image to artifactory..."
docker push   $IMG:$TAG

echo Built and published: $IMG:$TAG
