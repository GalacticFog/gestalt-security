#!/bin/bash

set -e 

docker build -t galacticfog/test-ldap:0.1.0 .
docker push galacticfog/test-ldap:0.1.0 
