package com.galacticfog.gestalt.security.test

import com.galacticfog.gestalt.security.api._
import com.galacticfog.gestalt.security.api.errors.BadRequestException
import play.api.libs.json.Json

class LDAPSpecs extends SpecWithSDK {

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
