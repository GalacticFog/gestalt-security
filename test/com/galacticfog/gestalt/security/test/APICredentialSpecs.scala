package com.galacticfog.gestalt.security.test

import java.util.UUID

import com.galacticfog.gestalt.security.api.GestaltToken.ACCESS_TOKEN
import com.galacticfog.gestalt.security.api.{GestaltOrg, _}
import com.galacticfog.gestalt.security.api.errors.{BadRequestException, UnauthorizedAPIException}
import com.galacticfog.gestalt.security.api.json.JsonImports._
import com.galacticfog.gestalt.security.data.APIConversions._
import com.galacticfog.gestalt.security.data.domain._
import com.galacticfog.gestalt.security.data.model.APICredentialRepository

class APICredentialSpecs extends SpecWithSDK {

  "Org API credentials" should {

    lazy val orgName = "api-creds-testing-org"
    lazy val org = await(rootOrg.createSubOrg(GestaltOrgCreate(
      name = orgName,
      createDefaultUserGroup = true
    )))
    lazy val subOrg = await(org.createSubOrg(GestaltOrgCreate(
      name = "sub",
      createDefaultUserGroup = true
    )))
    lazy val orgDir = await(org.listDirectories()).head

    "cannot be generated using token authentication" in {
      await(GestaltAccount.generateAPICredentials(rootAccount.id)(tokenSdk)) must throwA[BadRequestException]
    }

    "can be generated with no explicitly bound org and used to auth" in {
      val apiKey = await(GestaltAccount.generateAPICredentials(rootAccount.id)(keySdk))
      apiKey.apiSecret must beSome
      apiKey.accountId must_== rootAccount.id
      apiKey.disabled must beFalse
      val apiSDK = tokenSdk.withCreds(GestaltBasicCredentials(apiKey.apiKey, apiKey.apiSecret.get))
      APICredentialFactory.findByAPIKey(apiKey.apiKey) must beSome (
        (apikey: APICredentialRepository) => apikey.issuedOrgId == Some(rootOrg.id)
      )
      await(GestaltAccount.getSelf()(apiSDK)).id must_== rootAccount.id
      await(GestaltOrg.getCurrentOrg()(apiSDK)).id must_== rootOrg.id
    }

    "can be deleted and become ineffective" in {
      val apiKey = await(GestaltAccount.generateAPICredentials(rootAccount.id)(keySdk))
      apiKey.apiSecret must beSome
      apiKey.accountId must_== rootAccount.id
      apiKey.disabled must beFalse
      val apiSDK = tokenSdk.withCreds(GestaltBasicCredentials(apiKey.apiKey, apiKey.apiSecret.get))
      await(GestaltAccount.getSelf()(apiSDK)).id must_== rootAccount.id
      await(apiKey.delete()) must beTrue
      await(GestaltAccount.getSelf()(apiSDK)) must throwA[UnauthorizedAPIException]
    }

    "will cascade delete to child API keys" in {
      val apiKey1 = await(GestaltAccount.generateAPICredentials(rootAccount.id)(keySdk))
      val sdk1 = tokenSdk.withCreds(GestaltBasicCredentials(apiKey1.apiKey, apiKey1.apiSecret.get))
      val apiKey2 = await(GestaltAccount.generateAPICredentials(rootAccount.id)(sdk1))
      val sdk2 = tokenSdk.withCreds(GestaltBasicCredentials(apiKey2.apiKey, apiKey2.apiSecret.get))
      val apiKey3 = await(GestaltAccount.generateAPICredentials(rootAccount.id)(sdk2))
      val sdk3 = tokenSdk.withCreds(GestaltBasicCredentials(apiKey3.apiKey, apiKey3.apiSecret.get))
      await(apiKey1.delete()) must beTrue
      await(GestaltAccount.getSelf()(sdk1)) must throwA[UnauthorizedAPIException]
      await(GestaltAccount.getSelf()(sdk2)) must throwA[UnauthorizedAPIException]
      await(GestaltAccount.getSelf()(sdk3)) must throwA[UnauthorizedAPIException]
      APICredentialFactory.findByAPIKey(apiKey1.apiKey) must beNone
      APICredentialFactory.findByAPIKey(apiKey2.apiKey) must beNone
      APICredentialFactory.findByAPIKey(apiKey3.apiKey) must beNone
    }

    "can be exchanged for oauth tokens via client_credentials flow (global)" in {
      val ar = await(GestaltToken.grantClientToken()(keySdk))
      ar must beSome
      val token = ar.get.accessToken
      val intro = await(GestaltToken.validateToken(token))
      intro must beAnInstanceOf[ValidTokenResponse]
      val valid = intro.asInstanceOf[ValidTokenResponse]
      valid.gestalt_org_id must_== rootOrg.id
    }

    "can be exchanged for oauth tokens via client_credentials flow (fqon)" in {
      val ar = await(GestaltToken.grantClientToken(rootOrg.fqon)(keySdk))
      ar must beSome
      val token = ar.get.accessToken
      val intro = await(GestaltToken.validateToken(token))
      intro must beAnInstanceOf[ValidTokenResponse]
      val valid = intro.asInstanceOf[ValidTokenResponse]
      valid.gestalt_org_id must_== rootOrg.id
    }

    "can be exchanged for oauth tokens via client_credentials flow (orgId)" in {
      val ar = await(GestaltToken.grantClientToken(rootOrg.id)(keySdk))
      ar must beSome
      val token = ar.get.accessToken
      val intro = await(GestaltToken.validateToken(token))
      intro must beAnInstanceOf[ValidTokenResponse]
      val valid = intro.asInstanceOf[ValidTokenResponse]
      valid.gestalt_org_id must_== rootOrg.id
    }

    "will cascade delete to child oauth tokens" in {
      val apikey = await(GestaltAccount.generateAPICredentials(rootAccount.id)(keySdk))
      val sdkKey = tokenSdk.withCreds(GestaltBasicCredentials(apikey.apiKey, apikey.apiSecret.get))
      val childToken = await(GestaltToken.grantClientToken()(sdkKey))
      val apiChildToken = tokenSdk.withCreds(GestaltBearerCredentials(OpaqueToken(childToken.get.accessToken.id,ACCESS_TOKEN).toString))
      await(GestaltAccount.getSelf()(sdkKey)).id        must_== rootAccount.id
      await(GestaltAccount.getSelf()(apiChildToken)).id must_== rootAccount.id
      // delete
      await(GestaltAPIKey.delete(apikey.apiKey)) must beTrue
      // test effect
      APICredentialFactory.findByAPIKey(apikey.apiKey)          must beNone
      TokenFactory.findValidById(childToken.get.accessToken.id) must beNone
      await(GestaltAccount.getSelf()(sdkKey)).id        must throwA[UnauthorizedAPIException]
      await(GestaltAccount.getSelf()(apiChildToken)).id must throwA[UnauthorizedAPIException]
    }

    "can be generated with bound org for use in sync and current" in {
      val apiKey = await(GestaltAccount.generateAPICredentials(rootAccount.id, Some(subOrg.id))(keySdk))
      apiKey.apiSecret must beSome
      apiKey.accountId must_== rootAccount.id
      apiKey.disabled must beFalse
      APICredentialFactory.findByAPIKey(apiKey.apiKey) must beSome (
        (apikey: APICredentialRepository) => apikey.issuedOrgId.exists(_.asInstanceOf[UUID] == subOrg.id)
      )
      val apiSDK = tokenSdk.withCreds(GestaltBasicCredentials(apiKey.apiKey, apiKey.apiSecret.get))
      await(GestaltAccount.getSelf()(apiSDK)).id must_== rootAccount.id
      await(GestaltOrg.getCurrentOrg()(apiSDK)).id must_== subOrg.id
      await(GestaltOrg.syncOrgTree(None)(apiSDK)) must ( (sync: GestaltOrgSync) => sync.orgs(0).id == subOrg.id )
    }

    "fail for invalid bound org" in {
      await(GestaltAccount.generateAPICredentials(rootAccount.id, Some(UUID.randomUUID()))) must throwA[BadRequestException]
    }

    "fail for bound org with no membership" in {
      val newAccount = await(GestaltOrg.createAccount(subOrg.id, GestaltAccountCreateWithRights(
        username = "test-account",
        firstName = "test",
        lastName = "account",
        credential = GestaltPasswordCredential("letmein")
      )))
      AccountFactory.getAppAccount( AppFactory.findServiceAppForOrg(rootOrg.id).get.id.asInstanceOf[UUID], newAccount.id ) must beNone
      await(GestaltAccount.generateAPICredentials(newAccount.id, Some(rootOrg.id))) must throwA[BadRequestException]
    }

  }

  step({
    server.stop()
  })
}
