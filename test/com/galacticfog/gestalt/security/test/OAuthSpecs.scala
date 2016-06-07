package com.galacticfog.gestalt.security.test

import java.util.UUID

import com.galacticfog.gestalt.security.api.AccessTokenResponse.BEARER
import com.galacticfog.gestalt.security.api.GestaltToken.ACCESS_TOKEN
import com.galacticfog.gestalt.security.api.errors.OAuthError
import com.galacticfog.gestalt.security.api.{GestaltOrg, _}
import com.galacticfog.gestalt.security.data.model.TokenRepository
import org.joda.time.DateTime

import scala.concurrent.Future

class OAuthSpecs extends SpecWithSDK {

  lazy val orgName = "new-org-for-oauth"
  lazy val org = await(rootOrg.createSubOrg(GestaltOrgCreate(
    name = orgName,
    createDefaultUserGroup = true
  )))
  lazy val subOrg = await(org.createSubOrg(GestaltOrgCreate(
    name = "sub",
    createDefaultUserGroup = false // will map group from existing directory
  )))
  lazy val subSubOrg = await(subOrg.createSubOrg(GestaltOrgCreate(
    name = "sub",
    createDefaultUserGroup = false // will map group from existing directory
  )))
  lazy val orgDir = await(org.listDirectories()).head
  lazy val group = await(orgDir.createGroup(GestaltGroupCreate(
    name = "users"
  )))
  lazy val subOrgMapping = await(subOrg.mapAccountStore(GestaltAccountStoreMappingCreate(
    name = "mapping",
    description = None,
    storeType = GROUP,
    accountStoreId = group.id,
    isDefaultAccountStore = false,
    isDefaultGroupStore = false
  )))
  lazy val rights = Seq(GestaltGrantCreate(
    grantName = "grantA"
  ), GestaltGrantCreate(
    grantName = "grantB", grantValue = Some("grantBvalue")
  ))
  lazy val account = await(GestaltOrg.createAccount(org.id, GestaltAccountCreateWithRights(
    username = "new-org-account",
    firstName = "Account",
    lastName = "InNewOrg",
    groups = Some(Seq(group.id)),
    rights = Some(rights),
    credential = GestaltPasswordCredential("letmein")
  )))

  lazy val token = await(GestaltToken.grantPasswordToken(org.id, account.username, "letmein"))

  "Org oauth2" should {

    "not issue access tokens on invalid credentials (UUID)" in {
      await(GestaltToken.grantPasswordToken(org.id, account.username, "bad password")) must beNone
    }

    "not issue access tokens on invalid credentials (FQON)" in {
      await(GestaltToken.grantPasswordToken(org.fqon, account.username, "bad password")) must beNone
    }

    "cannot be exchanged for tokens using client_credentials flow" in {
      val ar = await(GestaltToken.grantClientToken()(tokenSdk))
      ar must beNone
    }

    "issue access tokens on valid credentials (UUID)" in {
      val token = await(GestaltToken.grantPasswordToken(org.id, account.username, "letmein"))
      token must beSome
      token.get.tokenType must_== BEARER
    }

    "issue access tokens on valid credentials (FQON)" in {
      val token = await(GestaltToken.grantPasswordToken(org.fqon, account.username, "letmein"))
      token must beSome
      token.get.tokenType must_== BEARER
    }

    "rate limit token creation" in {
      val newAccount = await(GestaltOrg.createAccount(org.id, GestaltAccountCreateWithRights(
        username = "hack-me",
        firstName = "", lastName = "",
        credential = GestaltPasswordCredential("weak-password")
      )))
      val tokenFutures = (1 to 200) map {
        _ => GestaltToken.grantPasswordToken(org.fqon, newAccount.username, "weak-password")
      }
      await(Future.sequence(tokenFutures)) must contain (
        (tokenAttempt: Option[AccessTokenResponse]) => tokenAttempt.isEmpty
      )
    }

    "accept valid access token for authentication" in {
      val resp = await(GestaltOrg.getById(org.id)(tokenSdk.withCreds(GestaltBearerCredentials(OpaqueToken(token.get.accessToken.id, ACCESS_TOKEN).toString))))
      resp must beSome(org)
    }

    "validate valid access tokens (UUID) against generating org" in {
      val resp = await(GestaltToken.validateToken(org.id, token.get.accessToken))
      resp must beAnInstanceOf[ValidTokenResponse]
      resp.active must beTrue
      val validResp = resp.asInstanceOf[ValidTokenResponse]
      validResp.jti must_== token.get.accessToken.id
      validResp.active must beTrue
      validResp.gestalt_rights must contain(
        (r: GestaltRightGrant) => r.grantName == rights(0).grantName && r.grantValue == rights(0).grantValue
      )
      validResp.gestalt_rights must contain(
        (r: GestaltRightGrant) => r.grantName == rights(1).grantName && r.grantValue == rights(1).grantValue
      )
      validResp.gestalt_org_id must_== org.id
    }

    "validate valid access tokens (FQON) against generating org" in {
      val resp = await(GestaltToken.validateToken(org.fqon, token.get.accessToken))
      resp must beAnInstanceOf[ValidTokenResponse]
      resp.active must beTrue
      val validResp = resp.asInstanceOf[ValidTokenResponse]
      validResp.jti must_== token.get.accessToken.id
      validResp.active must beTrue
      validResp.gestalt_rights must contain(
        (r: GestaltRightGrant) => r.grantName == rights(0).grantName && r.grantValue == rights(0).grantValue
      )
      validResp.gestalt_rights must contain(
        (r: GestaltRightGrant) => r.grantName == rights(1).grantName && r.grantValue == rights(1).grantValue
      )
      validResp.gestalt_org_id must_== org.id
    }

    "validate valid access tokens (global) against generating org" in {
      val resp = await(GestaltToken.validateToken(token.get.accessToken))
      resp must beAnInstanceOf[ValidTokenResponse]
      resp.active must beTrue
      val validResp = resp.asInstanceOf[ValidTokenResponse]
      validResp.jti must_== token.get.accessToken.id
      validResp.active must beTrue
      validResp.gestalt_rights must contain(
        (r: GestaltRightGrant) => r.grantName == rights(0).grantName && r.grantValue == rights(0).grantValue
      )
      validResp.gestalt_rights must contain(
        (r: GestaltRightGrant) => r.grantName == rights(1).grantName && r.grantValue == rights(1).grantValue
      )
      validResp.gestalt_org_id must_== org.id
    }

    "require authentication for introspection" in {
      val badSdk = tokenSdk.withCreds(GestaltBasicCredentials("bad-key","bad-secret"))
      await(GestaltToken.validateToken(token.get.accessToken)(badSdk)) must throwA[OAuthError]
    }

    "validate valid access tokens (UUID) against non-generating subscribed org" in {
      subOrgMapping.storeId must_== group.id
      val resp = await(GestaltToken.validateToken(subOrg.id, token.get.accessToken))
      resp must beAnInstanceOf[ValidTokenResponse]
      resp.active must beTrue
      val validResp = resp.asInstanceOf[ValidTokenResponse]
      validResp.jti must_== token.get.accessToken.id
      validResp.active must beTrue
      validResp.gestalt_rights must beEmpty
      validResp.gestalt_org_id must_== subOrg.id
    }

    "validate valid access tokens (FQON) against non-generating subscribed org" in {
      subOrgMapping.storeId must_== group.id
      val resp = await(GestaltToken.validateToken(subOrg.fqon, token.get.accessToken))
      resp must beAnInstanceOf[ValidTokenResponse]
      resp.active must beTrue
      val validResp = resp.asInstanceOf[ValidTokenResponse]
      validResp.jti must_== token.get.accessToken.id
      validResp.active must beTrue
      validResp.gestalt_rights must beEmpty
      validResp.gestalt_org_id must_== subOrg.id
    }

    "not validate invalid access tokens (UUID)" in {
      val resp = await(GestaltToken.validateToken(org.id, OpaqueToken(UUID.randomUUID(), ACCESS_TOKEN)))
      resp must_== INVALID_TOKEN
      resp.active must beFalse
    }

    "not validate invalid access tokens (FQON)" in {
      val resp = await(GestaltToken.validateToken(org.fqon, OpaqueToken(UUID.randomUUID(), ACCESS_TOKEN)))
      resp must_== INVALID_TOKEN
      resp.active must beFalse
    }

    "not validate invalid access tokens (global)" in {
      val resp = await(GestaltToken.validateToken(OpaqueToken(UUID.randomUUID(), ACCESS_TOKEN)))
      resp must_== INVALID_TOKEN
      resp.active must beFalse
    }

    "delete expired tokens on introspection" in {
      val maybeTokenResponse = await(GestaltToken.grantPasswordToken(org.fqon, account.username, "letmein"))
      maybeTokenResponse must beSome
      maybeTokenResponse.get.tokenType must_== BEARER
      val accessToken = maybeTokenResponse.get.accessToken
      await(GestaltToken.validateToken(org.fqon, accessToken)) must beAnInstanceOf[ValidTokenResponse]
      val tokendao = TokenRepository.find(accessToken.id)
      tokendao must beSome
      tokendao foreach { t => TokenRepository.save(t.copy(
        expiresAt = DateTime.now.minusMillis(1)
      ))}
      await(GestaltToken.validateToken(org.fqon, accessToken)) must_== INVALID_TOKEN
      TokenRepository.find(accessToken.id) must beNone
    }

    "allow explicit token deletion" in {
      val maybeTokenResponse = await(GestaltToken.grantPasswordToken(org.fqon, account.username, "letmein"))
      maybeTokenResponse must beSome
      maybeTokenResponse.get.tokenType must_== BEARER
      val accessToken = maybeTokenResponse.get.accessToken
      await(GestaltToken.validateToken(org.fqon, accessToken)) must beAnInstanceOf[ValidTokenResponse]
      await(GestaltToken.deleteToken(accessToken.id, accessToken.tokenType)) must beTrue
      await(GestaltToken.validateToken(org.fqon, accessToken)) must_== INVALID_TOKEN
      TokenRepository.find(accessToken.id) must beNone
    }

    "not validate token if account doesn't belong to org (UUID)" in {
      val resp = await(GestaltToken.validateToken(subSubOrg.id, OpaqueToken(UUID.randomUUID(), ACCESS_TOKEN)))
      resp must_== INVALID_TOKEN
      resp.active must beFalse
    }

    "not validate token if account doesn't belong to org (FQON)" in {
      val resp = await(GestaltToken.validateToken(subSubOrg.id, OpaqueToken(UUID.randomUUID(), ACCESS_TOKEN)))
      resp must_== INVALID_TOKEN
      resp.active must beFalse
    }

  }

  step({
    server.stop()
  })
}
