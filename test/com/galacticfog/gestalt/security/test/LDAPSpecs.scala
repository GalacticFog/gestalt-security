package com.galacticfog.gestalt.security.test

import java.util.UUID

import com.galacticfog.gestalt.security.api._
import com.galacticfog.gestalt.security.api.errors.BadRequestException
import com.galacticfog.gestalt.security.data.domain._
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
    lazy val ldapDirDAO = DirectoryFactory.find(ldapDir.id).get

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

    "be able to find groups (raw ldap)" in {
      val ldap = ldapDirDAO.asInstanceOf[LDAPDirectory]
      ldap.ldapFindGroupnamesByName("Scientists") must beASuccessfulTry(containTheSameElementsAs(Seq("Scientists")))
      ldap.ldapFindGroupnamesByName("*ists") must beASuccessfulTry(containTheSameElementsAs(Seq("Chemists", "Scientists")))
      ldap.ldapFindGroupnamesByName("*a*") must beASuccessfulTry(containTheSameElementsAs(Seq("Italians", "Mathematicians")))
      ldap.ldapFindGroupnamesByName("*") must beASuccessfulTry(containTheSameElementsAs(Seq("Italians", "Mathematicians", "Scientists", "Chemists")))
    }

    "be able to find groups by account (raw ldap)" in {
      val ldap = ldapDirDAO.asInstanceOf[LDAPDirectory]
      ldap.ldapFindGroupnamesByUser("read-only-admin") must beASuccessfulTry(beEmpty[List[String]])
      ldap.ldapFindGroupnamesByUser("nobel") must beASuccessfulTry(containTheSameElementsAs(Seq("Chemists")))
      ldap.ldapFindGroupnamesByUser("tesla") must beASuccessfulTry(containTheSameElementsAs(Seq("Scientists","Italians")))
    }

    "NOT allow a new group to be created" in {
      await(ldapDir.createGroup(GestaltGroupCreate("testGroup3"))) must throwA[BadRequestException](".*Group create request not valid.*")
    }

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

    "shadow groups on search" in {
      // verify none are shadowed
      GroupFactory.listByDirectoryId(ldapDir.id).map(_.name) must not contain(
        anyOf("Chemists", "Scientists", "Mathematicians", "Italians")
      )
      // ... and won't be returned in the list of shadowed groups
      await(newOrg.listGroups()) map(_.name) must not contain(
        anyOf("Chemists", "Scientists", "Mathematicians", "Italians")
      )
      // but a query will discover them
      val q = await(newOrg.listGroups(
        "name" -> "*ists"
      ))
      q.map(_.name) must contain(allOf("Chemists", "Scientists"))
      GroupFactory.listByDirectoryId(ldapDir.id).map(_.name) must contain(
        allOf("Chemists", "Scientists")
      )
      GroupFactory.listByDirectoryId(ldapDir.id).map(_.name) must not contain(
        anyOf("Mathematicians", "Italians")
      )
      // at which point in time, they will be shadowed
      // ... and returned from the simple listing
      await(newOrg.listGroups()) map(_.name) must contain(allOf("Chemists", "Scientists"))
      await(newOrg.listGroups()) map(_.name) must not contain(anyOf("Mathematicians","Italians"))
      // another search won't harm existing shadowed groups
      await(newOrg.listGroups(
        "name" -> "Italians"
      )).map(_.name) must contain(allOf("Italians"))
      await(newOrg.listGroups()) map(_.name) must contain(allOf("Chemists", "Scientists", "Italians"))
      await(newOrg.listGroups()) map(_.name) must not contain("Mathematicians")
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

    "not allow account to authenticate against a password not in LDAP" in {
      val token = await(GestaltToken.grantPasswordToken(newOrg.id, "not-newton", "password"))
       token must beNone
    }

    "allow shadowed account to be disabled/enabled with respect to authentication" in {
      val account = ldapDirDAO.lookupAccounts(username = Some("newton")).head
      // disable
      ldapDirDAO.disableAccount(account.id.asInstanceOf[UUID], disabled = true)
      AccountFactory.authenticate(newOrgApp.id, GestaltBasicCredsToken("newton", "password")) must beNone
      // re-enable
      ldapDirDAO.disableAccount(account.id.asInstanceOf[UUID], disabled = false)
      AccountFactory.authenticate(newOrgApp.id, GestaltBasicCredsToken("newton", "password")) must beSome
    }

    "refuse to perform local password checking" in {
      val account = ldapDirDAO.lookupAccounts(username = Some("newton")).head
      account.hashMethod must_== "shadowed"
      account.secret must_== ""
      AccountFactory.checkPassword(account, "") must beFalse
    }

    "shadow accounts on search" in {
      AccountFactory.directoryLookup(ldapDir.id, "euclid") must beNone
      AccountFactory.directoryLookup(ldapDir.id, "euler") must beNone
      await(newOrg.listAccounts()) map(_.username) must not contain(anyOf("euclid", "euler"))
      val q = await(newOrg.listAccounts(
        "username" -> "eu*"
      ))
      q.map(_.username) must containTheSameElementsAs(Seq("euclid", "euler"))
      ldapDirDAO.lookupAccounts(username=Some("euclid")) must haveSize(1)
      ldapDirDAO.lookupAccounts(username=Some("euler")) must haveSize(1)
      await(newOrg.listAccounts()) map(_.username) must contain(allOf("euclid", "euler"))
    }

  }

  step({
    server.stop()
  })
}
