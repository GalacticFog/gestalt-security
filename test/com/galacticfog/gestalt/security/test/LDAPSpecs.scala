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
    }

//    "allow user to create sub-org with LDAP directory" in {
//      val ldapOrg = newOrg.createSubOrg(GestaltOrgCreate("LdapTest", false, Some("A test sub-organization with LDAP integration.")))
//      val ldapDir = newOrg.createDirectory(GestaltDirectoryCreate("LdapTestDir", DIRECTORY_TYPE_LDAP))
//    }

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
      val token = await(GestaltToken.grantPasswordToken(newOrg.id, "newton", "password"))
      token must beSome
      // verify account is shadowed
      val newton = AccountFactory.directoryLookup(ldapDir.id, "newton")
      newton must beSome(
        (uar: UserAccountRepository) =>
          uar.username == "newton" && uar.dirId == ldapDir.id
      )
      TokenFactory.findValidById(token.get.accessToken.id) must beSome(
        (t: TokenRepository) => t.accountId == newton.get.id
      )
      // check that already shadowed account can be authenticated
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
