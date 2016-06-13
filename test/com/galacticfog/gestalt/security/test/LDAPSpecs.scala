package com.galacticfog.gestalt.security.test

import java.util.UUID

import com.galacticfog.gestalt.security.EnvConfig
import com.galacticfog.gestalt.security.api._
import com.galacticfog.gestalt.security.api.errors.BadRequestException
import com.galacticfog.gestalt.security.data.domain._
import com.galacticfog.gestalt.security.data.model.{UserGroupRepository, GroupMembershipRepository, TokenRepository, UserAccountRepository}
import play.api.libs.json.Json

class LDAPSpecs extends SpecWithSDK {

  lazy val newOrgName = "new-org-for-ldap-testing"
  lazy val newOrg = await(rootOrg.createSubOrg(GestaltOrgCreate(
    name = newOrgName,
    createDefaultUserGroup = false
  )))
  lazy val newOrgApp = await(newOrg.getServiceApp())

  val ldapUrl = EnvConfig.getEnvOpt("TEST_LDAP_URL") getOrElse "ldap://localhost:389"
  val ldapUser = EnvConfig.getEnvOpt("TEST_LDAP_USER") getOrElse "admin"
  val ldapPass = EnvConfig.getEnvOpt("TEST_LDAP_PASS") getOrElse "***REMOVED***"

  "LDAP Directory" should {

    val config = Json.parse(s"""
      |{
      |  "activeDirectory" : false,
      |  "url" : "$ldapUrl",
      |  "searchBase" : "dc=example,dc=com",
      |  "systemUsername" : "$ldapUser",
      |  "systemPassword" : "$ldapPass",
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

    "be able to find accounts by group (raw ldap)" in {
      val ldap = ldapDirDAO.asInstanceOf[LDAPDirectory]
      ldap.ldapFindUserDNsByGroup("Mathematicians") must beASuccessfulTry(containTheSameElementsAs(Seq(
        "uid=test,dc=example,dc=com","uid=gauss,dc=example,dc=com","uid=euler,dc=example,dc=com","uid=riemann,dc=example,dc=com","uid=euclid,dc=example,dc=com"
      )))
      ldap.ldapFindUserDNsByGroup("Chemists") must beASuccessfulTry(containTheSameElementsAs(Seq(
        "uid=pasteur,dc=example,dc=com","uid=nobel,dc=example,dc=com","uid=boyle,dc=example,dc=com","uid=curie,dc=example,dc=com"
      )))
      ldap.ldapFindUserDNsByGroup("Scientists") must beASuccessfulTry(containTheSameElementsAs(Seq(
        "uid=newton,dc=example,dc=com", "uid=tesla,dc=example,dc=com", "uid=galieleo,dc=example,dc=com", "uid=einstein,dc=example,dc=com"
      )))
      ldap.ldapFindUserDNsByGroup("Italians") must beASuccessfulTry(containTheSameElementsAs(Seq(
        "uid=tesla,dc=example,dc=com"
      )))
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

    "shadow accounts on search" in {
      // verify account is not shadowed
      AccountFactory.findInDirectoryByName(ldapDir.id, "newton") must beNone
      val lookup = ldapDirDAO.lookupAccounts(username = Some("newton"))
      lookup map {_.username} must containTheSameElementsAs(Seq("newton"))
      // verify account is shadowed
      AccountFactory.findInDirectoryByName(ldapDir.id, "newton") must beSome((uar: UserAccountRepository) =>
        uar.username == "newton" && uar.dirId == ldapDir.id && uar.id == lookup.head.id
      )
    }

    "shadow and authenticate user in LDAP and authenticate user already shadowed" in {
      // unshadow account, verify it's not shadowed
      AccountFactory.findInDirectoryByName(ldapDir.id, "newton") foreach {_.destroy()}
      AccountFactory.findInDirectoryByName(ldapDir.id, "newton") must beNone
      val maybeAuthAccount = AccountFactory.authenticate(newOrgApp.id, GestaltBasicCredsToken("newton", "password"))
      maybeAuthAccount must beSome( (uar: UserAccountRepository) =>
          uar.username == "newton" && uar.dirId == ldapDir.id
      )
      // verify account is shadowed
      AccountFactory.findInDirectoryByName(ldapDir.id, "newton") must beSome((uar: UserAccountRepository) =>
          uar.username == "newton" && uar.dirId == ldapDir.id && uar.id == maybeAuthAccount.get.id
      )
      // check that already shadowed account can be authenticated and get token
      await(GestaltToken.grantPasswordToken(newOrg.id, "newton", "password")) must beSome
    }

    "not allow inexistant account to authenticate" in {
      val token = await(GestaltToken.grantPasswordToken(newOrg.id, "not-newton", "password"))
       token must beNone
    }

    "not allow account to authenticate with bad password" in {
      val token = await(GestaltToken.grantPasswordToken(newOrg.id, "newton", "not-password"))
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

    "lookup accounts in a group" in {
      // unshadow account, verify it's not shadowed
      AccountFactory.findInDirectoryByName(ldapDir.id, "newton") foreach {_.destroy()}
      AccountFactory.findInDirectoryByName(ldapDir.id, "newton") must beNone
      val mathematicians = ldapDirDAO.lookupGroups("Mathematicians").head
      val query = ldapDirDAO.lookupAccounts(
        group = Some(mathematicians),
        username = Some("e*")
      )
      // contain euclid and euler, but not einstein
      query.map(_.username) must containTheSameElementsAs(Seq("euclid", "euler"))
    }

    "shadow accounts on search" in {
      AccountFactory.findInDirectoryByName(ldapDir.id, "euclid") foreach {_.destroy()}
      AccountFactory.findInDirectoryByName(ldapDir.id, "euler") foreach {_.destroy()}
      AccountFactory.findInDirectoryByName(ldapDir.id, "euclid") must beNone
      AccountFactory.findInDirectoryByName(ldapDir.id, "euler") must beNone
      await(newOrg.listAccounts()) map(_.username) must not contain(anyOf("euclid", "euler"))
      val q = await(newOrg.listAccounts(
        "username" -> "eu*"
      ))
      q.map(_.username) must containTheSameElementsAs(Seq("euclid", "euler"))
      ldapDirDAO.lookupAccounts(username=Some("euclid")) must haveSize(1)
      ldapDirDAO.lookupAccounts(username=Some("euler")) must haveSize(1)
      await(newOrg.listAccounts()) map(_.username) must contain(allOf("euclid", "euler"))
    }

    "shadow account memberships on account search" in {
      AccountFactory.findInDirectoryByName(ldapDir.id, "tesla") foreach {_.destroy()}
      AccountFactory.findInDirectoryByName(ldapDir.id, "tesla") must beNone
      await(newOrg.listAccounts()) map(_.username) must not contain("tesla")
      val q = await(newOrg.listAccounts(
        "username" -> "tesla"
      ))
      q.map(_.username) must containTheSameElementsAs(Seq("tesla"))
      ldapDirDAO.lookupAccounts(username=Some("tesla")) must haveSize(1)
      val tesla = await(newOrg.listAccounts()) find(_.username == "tesla")
      tesla must beSome
      await(tesla.get.listGroupMemberships()) map {_.name} must containTheSameElementsAs(Seq(
        "Scientists", "Italians"
      ))
    }

    "be capable of updating stale group memberships" in {
      val ldap = ldapDirDAO.asInstanceOf[LDAPDirectory]
      val newtonIsIn = Seq("Scientists")
      val newtonIsNotIn = Seq("Chemists", "Mathematicians", "Italians")   // Newton is a Mathematician, though!
      val newton = ldap.lookupAccounts(username = Some("newton")).head
      val groups = await(newOrg.listGroups("name" -> "*"))
      groups map {_.name} must contain( allOf((newtonIsIn ++ newtonIsNotIn):_*) )
      // remove shadow newton from Scientists
      groups filter {g => newtonIsIn.contains(g.name)} foreach {
        g => GroupFactory.removeAccountFromGroup(groupId = g.id, accountId = newton.id.asInstanceOf[UUID])
      }
      // put shadow newton into Mathematicians, Chemists, Italians
      groups filter {g => newtonIsNotIn.contains(g.name)} foreach {
        g => GroupFactory.addAccountToGroup(groupId = g.id, accountId = newton.id.asInstanceOf[UUID])
      }

      val tryMemberships = ldap.updateAccountMemberships(account = newton)
      tryMemberships must beSuccessfulTry
      val membership = tryMemberships.get
      membership map {_.name} must contain(allOf(newtonIsIn:_*))
      membership map {_.name} must not contain(anyOf(newtonIsNotIn:_*))
    }

    "update stale group memberships on auth" in {
      val newtonIsIn = Seq("Scientists")
      val newtonIsNotIn = Seq("Chemists", "Mathematicians", "Italians")   // Newton is a Mathematician, though!
      val newton = await(newOrg.listAccounts("username" -> "newton")).head
      val groups = await(newOrg.listGroups("name" -> "*"))
      groups map {_.name} must contain( allOf((newtonIsIn ++ newtonIsNotIn):_*) )
      // remove shadow newton from Scientists
      groups filter {g => newtonIsIn.contains(g.name)} foreach {
        g => GroupFactory.removeAccountFromGroup(groupId = g.id, accountId = newton.id)
      }
      // put shadow newton into Mathematicians, Chemists, Italians
      groups filter {g => newtonIsNotIn.contains(g.name)} foreach {
        g => GroupFactory.addAccountToGroup(groupId = g.id, accountId = newton.id)
      }

      val maybeToken = await(GestaltToken.grantPasswordToken(newOrg.id, "newton", "password"))
      maybeToken must beSome

      val token = maybeToken.get

      GroupFactory.listAccountGroups(newton.id) map {_.name} must contain(allOf(newtonIsIn:_*))
      GroupFactory.listAccountGroups(newton.id) map {_.name} must not contain(anyOf(newtonIsNotIn:_*))

      val intro = await(GestaltToken.validateToken(newOrg.id, token.accessToken))
      intro.active must beTrue
      intro must beAnInstanceOf[ValidTokenResponse]
      val valid = intro.asInstanceOf[ValidTokenResponse]
      valid.gestalt_groups.map(_.name) must contain(allOf(newtonIsIn:_*))
      valid.gestalt_groups.map(_.name) must not contain(anyOf(newtonIsNotIn:_*))
    }

  }

  step({
    server.stop()
  })
}
