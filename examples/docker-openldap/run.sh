#!/bin/bash 

docker run -d -p 389:389 --name local-ldap galacticfog/test-ldap:0.1.0
