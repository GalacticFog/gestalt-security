package com.galacticfog.gestalt.security.test

import com.galacticfog.gestalt.security.api._
import com.galacticfog.gestalt.security.api.errors.BadRequestException
import com.galacticfog.gestalt.security.data.APIConversions._
import com.galacticfog.gestalt.security.data.domain._
import com.galacticfog.gestalt.security.data.model.APICredentialRepository
import play.api.libs.json.Json

class AppSpecs extends SpecWithSDK {

  "Org Apps" should {

    val testAppName = "test-app-in-root-org"
    lazy val testApp = await(rootOrg.createApp(GestaltAppCreate(
      name = testAppName
    )))

    "be created properly under orgs" in {
      testApp.isServiceApp must beFalse
      testApp.name must_== testAppName
      testApp.orgId must_== rootOrg.id
    }

    "show up in org app listing after creation" in {
      await(rootOrg.listApps) must contain(testApp)
    }

    "be available by name under org" in {
      await(rootOrg.getAppByName(testAppName)) must beSome(testApp)
    }

    "be available by id" in {
      await(GestaltApp.getById(testApp.id)) must beSome(testApp)
    }

    "properly fail on account creation with no default account store" in {
      await(testApp.createAccount(GestaltAccountCreateWithRights(
        username = "wfail",
        firstName = "Will",
        lastName = "Fail",
        credential = GestaltPasswordCredential("letmein"),
        groups = None,
        rights = None
      ))) must throwA[BadRequestException](".*application does not have a default account store.*")
    }

    "properly fail on group creation with no default account store" in {
      await(testApp.createGroup(GestaltGroupCreateWithRights(
        name = "will-fail-group",
        rights = None
      ))) must throwA[BadRequestException](".*specified app does not have a default group store.*")
    }

    "be capable of deletion" in {
      await(GestaltApp.deleteApp(testApp.id)) must beTrue
    }

    "not show up after deletion in org app listing" in {
      await(rootOrg.listApps) must not contain testApp
    }

    "not be available after deletion by id" in {
      await(GestaltApp.getById(testApp.id)) must beNone
    }

  }

  "LDAP Directory" should {

    val config = Json.parse(
	 """
          {
            "activeDirectory" : false,
            "url" : "ldap://ldap.forumsys.com:389",
            "searchBase" : "dc=example,dc=com",
            "systemUsername" : "read-only-admin",
            "systemPassword" : "password",
            "primaryField" : "uid"
          }
	 """.stripMargin)
    lazy val ldapdir = await(rootOrg.createDirectory(GestaltDirectoryCreate("LdapTestDir", DIRECTORY_TYPE_LDAP, Some("Test LDAP"), Some(config))))

    "be addable to the root org" in {
      ldapdir must haveName("LdapTestDir")
      await(rootOrg.listDirectories()) must contain(ldapdir)
    }

//    "allow user to create sub-org with LDAP directory" in {
//      val ldapOrg = rootOrg.createSubOrg(GestaltOrgCreate("LdapTest", false, Some("A test sub-organization with LDAP integration.")))
//      val ldapDir = rootOrg.createDirectory(GestaltDirectoryCreate("LdapTestDir", DIRECTORY_TYPE_LDAP))
//    }

//    "NOT allow a new group to be created" in {
//      await(ldapdir.createGroup(GestaltGroupCreate("testGroup3"))) must throwA[BadRequestException](".*cannot add group.*")
//    }

    "NOT allow a new user to be created" in {
      await(ldapdir.createAccount(GestaltAccountCreate(
        username = "testAccount3",
        firstName = "test",
        lastName = "account3",
        email = Some("testuser3@test.com"), phoneNumber = None,
        groups = None,
        credential = GestaltPasswordCredential(password = "letmein")
      ))) must throwA[BadRequestException](".*Account create request not valid.*")
    }

    "shadow and authenticate user in LDAP and authenticate user already shadowed" in {

      val token = await(GestaltToken.grantPasswordToken(rootOrg.id, "newton", "password"))
       token must beSome
      // Check account is shadowed
//      await(rootOrg.getAccountByUsername("newton")) must beSome
      // Check that already shadowed account can be authenticated
      val token2 = await(GestaltToken.grantPasswordToken(rootOrg.id, "newton", "password"))
       token2 must beSome
    }

    "NOT allow account to authenticate against a password not in LDAP" in {
      val token = await(GestaltToken.grantPasswordToken(rootOrg.id, "einstein", "letmein"))
       token must beNone
    }

  }

  step({
    server.stop()
  })
}
