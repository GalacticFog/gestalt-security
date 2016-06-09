package com.galacticfog.gestalt.security.test

import com.galacticfog.gestalt.security.api._
import com.galacticfog.gestalt.security.api.errors.BadRequestException
import com.galacticfog.gestalt.security.data.domain.{TokenFactory, AccountFactory}
import com.galacticfog.gestalt.security.data.model.{TokenRepository, UserAccountRepository}
import play.api.libs.json.Json

class LDAPSpecs extends SpecWithSDK {


  lazy val newOrgName = "new-org-for-ldap-testing"
  lazy val newOrg = await(rootOrg.createSubOrg(GestaltOrgCreate(
    name = newOrgName,
    createDefaultUserGroup = false
  )))
  lazy val newOrgApp = await(newOrg.getServiceApp())

  "LDAP Directory" should {

    val config = Json.parse("""
      |{
      |  "activeDirectory" : false,
      |  "url" : "ldap://ldap.forumsys.com:389",
      |  "searchBase" : "dc=example,dc=com",
      |  "systemUsername" : "read-only-admin",
      |  "systemPassword" : "password",
      |  "primaryField" : "uid"
      |}""".stripMargin)

    lazy val ldapDir = await(newOrg.createDirectory(GestaltDirectoryCreate("LdapTestDir", DIRECTORY_TYPE_LDAP, Some("Test LDAP"), Some(config))))

    "be addable to the root org" in {
      ldapDir must haveName("LdapTestDir")
      await(newOrg.listDirectories()) must contain(ldapDir)
      await(GestaltOrg.mapAccountStore(
        orgId = newOrg.id,
        createRequest = GestaltAccountStoreMappingCreate(
          name = "test-ldap-mapping",
          storeType = DIRECTORY,
          accountStoreId = ldapDir.id,
          isDefaultAccountStore = false,
          isDefaultGroupStore = false
        )
      )) must (
        (asm: GestaltAccountStoreMapping) => asm.storeType == DIRECTORY && asm.storeId == ldapDir.id
      )
    }

//    "NOT allow a new group to be created" in {
//      await(ldapdir.createGroup(GestaltGroupCreate("testGroup3"))) must throwA[BadRequestException](".*cannot add group.*")
//    }

    "NOT allow a new user to be created" in {
      await(ldapDir.createAccount(GestaltAccountCreate(
        username = "testAccount3",
        firstName = "test",
        lastName = "account3",
        email = Some("testuser3@test.com"), phoneNumber = None,
        groups = None,
        credential = GestaltPasswordCredential(password = "letmein")
      ))) must throwA[BadRequestException](".*Account create request not valid.*")
    }

    "shadow and authenticate user in LDAP and authenticate user already shadowed" in {
      // verify account is not shadowed
      AccountFactory.directoryLookup(ldapDir.id, "newton") must beNone
      val maybeAuthAccount = AccountFactory.authenticate(newOrgApp.id, GestaltBasicCredsToken("newton", "password"))
      maybeAuthAccount must beSome( (uar: UserAccountRepository) =>
          uar.username == "newton" && uar.dirId == ldapDir.id
      )
      // verify account is shadowed
      AccountFactory.directoryLookup(ldapDir.id, "newton") must beSome( (uar: UserAccountRepository) =>
          uar.username == "newton" && uar.dirId == ldapDir.id && uar.id == maybeAuthAccount.get.id
      )
      // check that already shadowed account can be authenticated and get token
      await(GestaltToken.grantPasswordToken(newOrg.id, "newton", "password")) must beSome
    }

    "NOT allow account to authenticate against a password not in LDAP" in {
      val token = await(GestaltToken.grantPasswordToken(newOrg.id, "not-newton", "password"))
       token must beNone
    }

  }

  step({
    server.stop()
  })
}
