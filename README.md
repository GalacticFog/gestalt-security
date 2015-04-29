# Gestalt Security Server

## REST Endpoints

All endpoints are secured and must be authenticated using a valid API key and secret. Currently, authentication is supported using Basic authentication.

### Get the default/current org for the caller
GET /orgs/current
RESP-STATUS 200 OK
RESP-BODY {"orgId":"DdqZR8D3VztAanAhDESqKNE4","orgName":"GalacticFog"}

### Get a list of orgs
GET /orgs/:orgId/apps
RESP-STATUS 200 OK
RESP-BODY
[
    {
        "appId": "MKXR5V1LM0sWu77dDoS3i4hm",
        "appName": "CallFairyScheduler",
        "orgId": "DdqZR8D3VztAanAhDESqKNE4"
    },
    {
        "appId": "TF1HJxn9uMtURqxNSgY76aH4",
        "appName": "CallFairyNotifier",
        "orgId": "DdqZR8D3VztAanAhDESqKNE4"
    },
    {
        "appId": "3VAv5fXHdNkT4wGTpz9jEZqR",
        "appName": "CallFairyCaller",
        "orgId": "DdqZR8D3VztAanAhDESqKNE4"
    }
]

### Get information on an app
GET /apps/:appId
RESP-STATUS 200 OK
RESP-BODY {"appId":"TF1HJxn9uMtURqxNSgY76aH4","appName":"CallFairyNotifier","orgId":"DdqZR8D3VztAanAhDESqKNE4"}

### Authenticate a user against an app
POST /apps/:appId/auth
PAYLOAD {"username":"someUserName", "password":"somePassword"}
RESP-STATUS 200 OK
RESP-BODY {"account":{"username":"launcher","firstName":"","lastName":"","email":""},"rights":[{"grantName":"gestalt-notifier:source:create"}]}

or, in case the user doesn't authenticate or isn't known to the app,
RESP-STATUS 403 FORBIDDEN
RESP-BODY <empty>
