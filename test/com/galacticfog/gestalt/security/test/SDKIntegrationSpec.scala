package com.galacticfog.gestalt.security.test

import java.util.UUID

import com.galacticfog.gestalt.security.{FlywayMigration, EnvConfig, InitRequest}
import com.galacticfog.gestalt.security.api.AccessTokenResponse.BEARER
import com.galacticfog.gestalt.security.api.GestaltToken.ACCESS_TOKEN
import com.galacticfog.gestalt.security.api._
import com.galacticfog.gestalt.security.api.errors.{BadRequestException, ConflictException, OAuthError, UnauthorizedAPIException}
import com.galacticfog.gestalt.security.api.json.JsonImports._
import com.galacticfog.gestalt.security.data.APIConversions._
import com.galacticfog.gestalt.security.data.domain._
import com.galacticfog.gestalt.security.data.model.{APICredentialRepository, TokenRepository}
import org.flywaydb.core.Flyway
import org.joda.time.DateTime
import org.specs2.matcher.{Expectable, MatchResult, Matcher}
import play.api.libs.json.Json
import play.api.libs.ws.WS
import play.api.test._
import com.galacticfog.gestalt.security.data.APIConversions._
import com.galacticfog.gestalt.security.api.json.JsonImports._
import com.galacticfog.gestalt.security.api.GestaltOrg

class SDKIntegrationSpec extends SpecWithSDK {

  "Root org" should {

    "be accessible by name" in {
      await(GestaltOrg.getByFQON("root")) must beSome(rootOrg)
    }

    "be accessible by id" in {
      await(GestaltOrg.getById(rootOrg.id)) must beSome(rootOrg)
    }

    "be accessible as current" in {
      await(GestaltOrg.getCurrentOrg()) must_== rootOrg
    }

    "appear in list of all orgs" in {
      val orgs = await(GestaltOrg.listOrgs())
      orgs must contain(exactly(rootOrg))
    }

    "be syncable via root admin" in {
      val sync = await(GestaltOrg.syncOrgTree(None))
      sync.orgs     must contain(exactly(rootOrg))
      sync.accounts must contain(exactly(rootAccount))
      sync.groups   must contain(exactly(rootAdminsGroup))

      await(GestaltOrg.syncOrgTree(Some(rootOrg.id))) must_== sync
    }

    "not perform framework authorization with username,password credentials" in {
      // against implicit root org
      val auth1 = await(GestaltOrg.authorizeFrameworkUser(rootAccountCreds))
      auth1 must beNone
      // against explicit root org
      val auth2 = await(GestaltOrg.authorizeFrameworkUser(rootOrg.fqon, rootAccountCreds))
      auth2 must beNone
      // against explicit org id
      val auth3 = await(GestaltOrg.authorizeFrameworkUser(rootOrg.id, rootAccountCreds))
      auth3 must beNone
    }

    "perform framework authorization equivalently" in {
      // against implicit root org
      val auth1 = await(GestaltOrg.authorizeFrameworkUser(rootBearerCreds))
      auth1 must beSome
      val ar = auth1.get
      // against explicit root org
      val auth2 = await(GestaltOrg.authorizeFrameworkUser(rootOrg.fqon, rootBearerCreds))
      auth2 must beSome(ar)
      // against explicit org id
      val auth3 = await(GestaltOrg.authorizeFrameworkUser(rootOrg.id, rootBearerCreds))
      auth3 must beSome(ar)
    }

    "not be capable of deletion" in {
      await(GestaltOrg.deleteOrg(rootOrg.id)) must throwA[BadRequestException](".*cannot delete root org.*")
    }

    "list root admin among accounts" in {
      await(rootOrg.listAccounts) must contain(exactly(rootAccount))
    }

    "get the root admin by username" in {
      await(rootOrg.getAccountByUsername(rootAccount.username)) must beSome(rootAccount)
    }

    "get the root account by account id" in {
      await(rootOrg.getAccountById(rootAccount.id)) must beSome(rootAccount)
    }

  }

  "Root dir" should {
    "contain the root account" in {
      await(rootDir.listAccounts()) must containTheSameElementsAs(Seq(rootAccount))
    }

    "be available by id" in {
      await(GestaltDirectory.getById(rootDir.id)) must beSome(rootDir)
    }
  }

  "Root app" should {

    lazy val rootApp: GestaltApp = AppFactory.findServiceAppForOrg(rootOrg.id).get
    lazy val appAuth = await(GestaltApp.authorizeUser(rootApp.id, GestaltBasicCredsToken(rootUsername,rootPassword))).get

    "be accessible from root org endpoint" in {
      await(rootOrg.getServiceApp) must_== rootApp
    }

    "show up in list of root org apps" in {
      val apps = await(rootOrg.listApps)
      apps must contain(exactly(rootApp))
    }

    "be accessible by id" in {
      await(GestaltApp.getById(rootApp.id)) must_== Some(rootApp)
    }

    "returns same rights as auth" in {
      await(GestaltApp.listAccountGrantsByUsername(rootApp.id, rootAccount.username) ) must containTheSameElementsAs(appAuth.rights)
      await(GestaltApp.listAccountGrants(rootApp.id, appAuth.account.id)) must containTheSameElementsAs(appAuth.rights)
    }

    "returns the same accounts as the root org" in {
      await(rootApp.listAccounts) must containTheSameElementsAs(await(rootOrg.listAccounts))
    }

    "not be capable of deletion" in {
      await(GestaltApp.deleteApp(rootApp.id)) must throwA[BadRequestException](".*cannot delete service app.*")
    }

    "authenticate equivalently to framework" in {
      appAuth must_== await(GestaltOrg.authorizeFrameworkUser(rootBearerCreds)).get
    }

    "get the root user by username" in {
      await(rootApp.getAccountByUsername(rootAccount.username)) must beSome(rootAccount)
    }

  }

  "Org naming constraints" should {

    "prohibit a mixed case name" in {
      val failure = GestaltOrg.createSubOrg(rootOrg.id, GestaltOrgCreate("hasAcapitalletter"))
      await(failure) must throwA[BadRequestException](".*org name is invalid.*")
    }

    "prohibit a name with a space" in {
      val failure = GestaltOrg.createSubOrg(rootOrg.id, GestaltOrgCreate("has space"))
      await(failure) must throwA[BadRequestException](".*org name is invalid.*")
    }

    "prohibit a name with preceding dash" in {
      val failure = GestaltOrg.createSubOrg(rootOrg.id, GestaltOrgCreate("-good-but-for-the-dash"))
      await(failure) must throwA[BadRequestException](".*org name is invalid.*")
    }

    "prohibit a name with a trailing dash" in {
      val failure = GestaltOrg.createSubOrg(rootOrg.id, GestaltOrgCreate("good-but-for-the-dash-"))
      await(failure) must throwA[BadRequestException](".*org name is invalid.*")
    }

    "prohibit a name with consecutive dash" in {
      val failure = GestaltOrg.createSubOrg(rootOrg.id, GestaltOrgCreate("good-but-for--the-dash"))
      await(failure) must throwA[BadRequestException](".*org name is invalid.*")
    }

  }

  "Right grants" should {

    "not permit empty grant names" in {
      await(GestaltOrg.addGrantToAccount(
        orgId = rootOrg.id,
        accountId = rootAccount.id,
        grant = GestaltGrantCreate(
          grantName = ""
        )
      )) must throwA[BadRequestException](".*right grant must be non-empty without leading or trailing spaces.*")
    }

    "not permit spaces at front of grant names" in {
      await(GestaltOrg.addGrantToAccount(
        orgId = rootOrg.id,
        accountId = rootAccount.id,
        grant = GestaltGrantCreate(
          grantName = " not-trimmed"
        )
      )) must throwA[BadRequestException](".*right grant must be non-empty without leading or trailing spaces.*")
    }

    "not permit spaces at back of grant names" in {
      await(GestaltOrg.addGrantToAccount(
        orgId = rootOrg.id,
        accountId = rootAccount.id,
        grant = GestaltGrantCreate(
          grantName = "not-trimmed "
        )
      )) must throwA[BadRequestException](".*right grant must be non-empty without leading or trailing spaces.*")
    }

  }

  "New Org Without Directory" should {

    lazy val newOrgName = "new-org-no-dir"
    lazy val newOrgDesc = "this is a description of an org"
    lazy val newOrg = await(rootOrg.createSubOrg(GestaltOrgCreate(
      name = newOrgName,
      createDefaultUserGroup = false,
      description = Some(newOrgDesc)
    )))
    lazy val newOrgApp = await(newOrg.getServiceApp())

    "not have root in fqon" in {
      newOrg must haveName(newOrgName)
      newOrg.fqon must_== newOrgName
    }

    "show up in the root org tree" in {
      await(rootOrg.listOrgs).map(_.id) must containTheSameElementsAs(Seq(rootOrg.id,newOrg.id))
    }

    "return its created description" in {
      await(GestaltOrg.getById(newOrg.id)).get.description must beSome(newOrgDesc)
    }

    "be alone in its own org tree" in {
      await(newOrg.listOrgs()).map(_.id) must containTheSameElementsAs(Seq(newOrg.id))
    }

    "not contain a new directory" in {
      val orgDirs = await(newOrg.listDirectories)
      orgDirs must beEmpty
      val rootDirs = await(rootOrg.listDirectories)
      rootDirs must haveSize(1) // no new dirs
      val dir = rootDirs.head
      val groups = await(dir.listGroups())
      groups must haveSize(2) // dir has a new group
    }

    "allow creator to authenticate by name" in {
      val authAttempt = await(GestaltOrg.authorizeFrameworkUser(newOrg.fqon, rootBearerCreds))
      authAttempt must beSome
      val auth = authAttempt.get
      auth.account must_== rootAccount
      auth.groups must haveSize(2)
      auth.orgId must_== newOrg.id
      auth.rights must not beEmpty
    }

    "contain a service app" in {
      newOrgApp.isServiceApp must beTrue
      newOrgApp.orgId must_== newOrg.id
    }

    "be unable to create another org with the same name" in {
      await(rootOrg.createSubOrg(GestaltOrgCreate(
        name = newOrgName,
        createDefaultUserGroup = false
      ))) must throwA[ConflictException](".*name already exists.*")
    }

    lazy val newOrgMappings = await(newOrgApp.listAccountStores)
    lazy val newOrgMapping  = newOrgMappings.head
    lazy val newOrgAdminGroup = await(GestaltGroup.getById(newOrgMapping.storeId))

    "contain an account store mapping to a group in the parent directory" in {
      newOrgMappings must haveSize(1)
      newOrgMapping.storeType must_== com.galacticfog.gestalt.security.api.GROUP
      newOrgAdminGroup must beSome

      await(GestaltAccountStoreMapping.getById(newOrgMapping.id)) must beSome(newOrgMapping)
    }

    "result in a new group membership for the creating account" in {
      val rootGroups = await(rootAccount.listGroupMemberships())
      rootGroups must contain(newOrgAdminGroup.get)
      rootGroups must haveSize(2)
    }

    "list equivalent org and service app mappings" in {
      await(newOrgApp.listAccountStores()) must containTheSameElementsAs(newOrgMappings)
    }

    "list the new group as an org group" in {
      await(newOrg.listGroups()) must contain(exactly(newOrgAdminGroup.get))
    }

    "list equivalent org and service app groups" in {
      val fromOrg = await(newOrg.listGroups())
      val fromApp = await(newOrgApp.listGroups())
      fromOrg must containTheSameElementsAs(fromApp)
    }

    "list the root admin as an org account" in {
      await(newOrg.listAccounts()) must contain(exactly(rootAccount))
    }

    "get the groups for the root admin" in {
      await(rootAccount.listGroupMemberships()) must containTheSameElementsAs(await(rootDir.listGroups()))
    }

    "verify right grants to the root admin via the new org admin group" in {
      val accountRights = await(newOrg.listAccountGrants(rootAccount.id))
      val groupRights   = await(newOrg.listGroupGrants(newOrgAdminGroup.get.id))
      accountRights must containTheSameElementsAs(groupRights)
    }

    "list equivalent account rights from service app and org" in {
      val fromOrg = await(newOrg.listAccountGrants(rootAccount.id))
      val fromApp = await(newOrgApp.listAccountGrants(rootAccount.id))
      fromOrg must containTheSameElementsAs(fromApp)
    }

    "list equivalent group rights from service app and org" in {
      val fromOrg = await(newOrg.listGroupGrants(newOrgAdminGroup.get.id))
      val fromApp = await(newOrgApp.listGroupGrants(newOrgAdminGroup.get.id))
      fromOrg must containTheSameElementsAs(fromApp)
    }

    "be unable to get the root admin by name via the org because there is no default account store" in {
      await(newOrg.getAccountByUsername(rootAccount.username)) must throwA[BadRequestException](".*does not have a default account store.*")
    }

    "get the new group by id via the org" in {
      await(newOrg.getGroupById(newOrgAdminGroup.get.id)) must beSome(newOrgAdminGroup.get)
    }

    "get the new group by the name in the directory" in {
      await(rootDir.getGroupByName(newOrgAdminGroup.get.name)) must beSome(newOrgAdminGroup.get)
    }

    "be unable to get the new group by name via the org because there is no default group store" in {
      await(newOrg.getGroupByName(newOrgAdminGroup.get.name)) must throwA[BadRequestException](".*does not have a default group store.*")
    }

    "include the root admin in the new org admin group" in {
      await(newOrgAdminGroup.get.listAccounts()) must contain(rootAccount)
    }

    "allow creator to delete org" in {
      await(GestaltOrg.deleteOrg(newOrg.id)) must beTrue
    }

    "not exist after deletion" in {
      await(GestaltOrg.getByFQON(newOrgName)) must beNone
    }

    "automatically remove admin group on deletion" in {
      val rootDirGroups = await(rootOrg.listDirectories() flatMap {_.head.listGroups()})
      rootDirGroups must haveSize(1)
    }

  }

  "New Org with Directory" should {

    lazy val newOrgName = "new-org-with-dir"
    lazy val newOrg = await(rootOrg.createSubOrg(GestaltOrgCreate(
      name = newOrgName,
      createDefaultUserGroup = true
    )))
    lazy val newOrgApp = await(newOrg.getServiceApp())
    lazy val newOrgDir = await(newOrg.listDirectories()).head

    "have a new directory" in {
      await(newOrg.listDirectories()) must haveSize(1)
    }

    "have a mapping for the new directory as the default group store" in {
      await(newOrgApp.listAccountStores()) must contain( (asm: GestaltAccountStoreMapping) =>
          asm.storeId == newOrgDir.id
            && asm.storeType == DIRECTORY
            && asm.isDefaultGroupStore
      )
    }

    "have a new group in the new directory with a procedural name with a mapping as the default account store" in {
      val dirGroups = await(newOrgDir.listGroups())
      dirGroups must haveSize(1)
      val newGroup = dirGroups.head
      newGroup.name must contain(newOrg.name)
      await(newOrgApp.listAccountStores()) must contain( (asm: GestaltAccountStoreMapping) =>
          asm.storeId == newGroup.id
            && asm.storeType == GROUP
            && asm.isDefaultAccountStore
      )
    }

    "have a group in the root directory for the root user, visible by ID but not by name" in {
      val rootGroupSeq = await(newOrg.listGroups).filter(_.directory.id == rootDir.id)
      rootGroupSeq must haveSize(1)
      val rootGroup = rootGroupSeq.head
      await(newOrg.getGroupById(rootGroup.id)) must beSome(rootGroup)
      await(newOrg.getGroupByName(rootGroup.name)) must beNone
    }

    "should list groups created directly in the directory" in {
      val manualGrp = await(newOrgDir.createGroup(GestaltGroupCreate(
        name = "manually-created-dir-group"
      )))
      await(newOrg.listGroups) must contain(manualGrp)
      await(newOrg.getGroupById(manualGrp.id)) must beSome(manualGrp)
      await(newOrg.getGroupByName(manualGrp.name)) must beSome(manualGrp)
    }

    "should list accounts created directly in the directory" in {
      val manualAccount = await(newOrgDir.createAccount(GestaltAccountCreate(
        username = "manual-user",
        firstName = "Manny",
        lastName = "User",
        groups = None,
        credential = GestaltPasswordCredential("letmein")
      )))
      await(newOrg.listAccounts) must contain(manualAccount)
      await(newOrg.getAccountById(manualAccount.id)) must beSome(manualAccount)
      await(newOrg.getAccountByUsername(manualAccount.username)) must beSome(manualAccount)
      manualAccount.directory.id must_== newOrgDir.id
    }

    "should be created in the appropriate org and directory" in {
      val manualAccount = await(GestaltOrg.createAccount(newOrg.id, GestaltAccountCreateWithRights(
        username = "manual-user-2",
        firstName = "Manny",
        lastName = "User",
        groups = None,
        credential = GestaltPasswordCredential("letmein")
      )))
      manualAccount.directory.id must_== newOrgDir.id
    }

    "should not add account in the case of a non-existent group" in {
      val failAccountName = "failedAccount"
      await(GestaltOrg.createAccount(newOrg.id, GestaltAccountCreateWithRights(
        username = failAccountName,
        firstName = "Will",
        lastName = "Fail",
        credential = GestaltPasswordCredential("letmein"),
        groups = Some(Seq(UUID.randomUUID())), // failure
        rights = None
      ))) must throwA[BadRequestException](".*cannot add account to non-existent group.*")
      await(newOrg.listAccounts) must not contain((a: GestaltAccount) => a.username == failAccountName)
    }

    "should not add account with invalid grant name" in {
      val failAccountName = "failedAccount"
      await(GestaltOrg.createAccount(newOrg.id, GestaltAccountCreateWithRights(
        username = failAccountName,
        firstName = "Will",
        lastName = "Fail",
        credential = GestaltPasswordCredential("letmein"),
        groups = None,
        rights = Some(Seq(GestaltGrantCreate(
          grantName = ""   // fail
        ))
      )))) must throwA[BadRequestException](".*right grant must be non-empty without leading or trailing spaces.*")
      await(newOrg.listAccounts) must not contain((a: GestaltAccount) => a.username == failAccountName)
    }

    "should not add group with invalid right grants" in {
      val failGroupName = "failedGroup"
      await(GestaltOrg.createGroup( newOrg.id, GestaltGroupCreateWithRights(
        name = failGroupName,
        rights = Some(Seq(GestaltGrantCreate(
          grantName = "" // fail
        )))
      ))) must throwA[BadRequestException](".*right grant must be non-empty without leading or trailing spaces.*")
      await(newOrg.listGroups) must not contain((g: GestaltGroup) => g.name == failGroupName)
    }

    "should create new groups in the new directory owned by the org" in {
      val newGroup = await(GestaltOrg.createGroup( newOrg.id, GestaltGroupCreateWithRights(
        name = "group-should-belong-to-new-org"
      )))
      newGroup.directory.orgId must_== newOrg.id
    }

    "cleanup" in {
      await(GestaltOrg.deleteOrg(newOrg.id)) must beTrue
    }

  }

  "SubOrg sync" should {

    lazy val newOrgName = "new-org"
    lazy val subOrgName = "sub-org"
    lazy val newOrg = await(rootOrg.createSubOrg(GestaltOrgCreate(
      name = newOrgName,
      createDefaultUserGroup = true
    )))
    lazy val subOrg = await(newOrg.createSubOrg(GestaltOrgCreate(
      name = subOrgName,
      createDefaultUserGroup = true
    )))
    lazy val newOrgGroup = await(GestaltOrg.createGroup(newOrg.id,
      GestaltGroupCreateWithRights("new-org-group", rights = None)))
    lazy val subOrgGroup1 = await(GestaltOrg.createGroup(subOrg.id,
      GestaltGroupCreateWithRights( name = "group-1", rights = None )))
    lazy val subOrgGroup2 = await(GestaltOrg.createGroup(subOrg.id,
      GestaltGroupCreateWithRights( name = "group-2", rights = None )))
    lazy val newOrgAccount = await(GestaltOrg.createAccount(newOrg.id,
      GestaltAccountCreateWithRights(
        username = "new-account",
        firstName = "", lastName = "", credential = GestaltPasswordCredential("letmein"),
        groups = Some(Seq(newOrgGroup.id)), rights = None
      )))
    lazy val subOrgAccount = await(GestaltOrg.createAccount(subOrg.id,
      GestaltAccountCreateWithRights(
        username = "sub-account",
        firstName = "", lastName = "", credential = GestaltPasswordCredential("letmein"),
        groups = Some(Seq(subOrgGroup1.id)), rights = None
      )))
    lazy val sync = await(GestaltOrg.syncOrgTree(Some(newOrg.id)))

    "precheck" in {
      newOrgAccount.directory.orgId must_== newOrg.id
      subOrgAccount.directory.orgId must_== subOrg.id
      newOrgGroup.directory.id must_== newOrgAccount.directory.id
      subOrgGroup1.directory.id must_== subOrgAccount.directory.id
      subOrgGroup2.directory.id must_== subOrgAccount.directory.id
    }

    "contain only orgs below sync point" in {
      sync.orgs must containTheSameElementsAs[GestaltOrg](Seq(newOrg, subOrg), _.id == _.id)
    }

    "contain creator and all local accounts" in {
      sync.accounts must containTheSameElementsAs[GestaltAccount](Seq(rootAccount, newOrgAccount, subOrgAccount), _.id == _.id)
    }

    "contain admins account and all local groups" in {
      sync.groups.map{_.id} must containAllOf(Seq(newOrgGroup.id, subOrgGroup1.id, subOrgGroup2.id))
      sync.groups.filter(g => g.directory.id == rootDir.id && g.name.endsWith("admins")) must haveSize(2)
      sync.groups.filter(g => g.name.endsWith("users")) must haveSize(2)
    }

    "contain membership from admin groups to creator" in {
      sync.groups.filter(_.name.endsWith("admins")).map(_.accounts) must containTheSameElementsAs(
        Seq(Seq(rootAccount.getLink()), Seq(rootAccount.getLink()))
      )
    }

    "contain membership from user groups to appropriate users" in {
      sync.groups.filter( _.name.endsWith("users")).map(_.accounts) must containTheSameElementsAs(
        Seq(Seq(newOrgAccount.getLink()), Seq(subOrgAccount.getLink()))
      )
    }

    "contain membership from manual groups to appropriate users" in {
      sync.groups.find(_.id == newOrgGroup.id).get.accounts must containTheSameElementsAs(Seq(newOrgAccount.getLink()))
      sync.groups.find(_.id == subOrgGroup1.id).get.accounts must containTheSameElementsAs(Seq(subOrgAccount.getLink()))
      sync.groups.find(_.id == subOrgGroup2.id).get.accounts must beEmpty
    }

    "cleanup" in {
      await(GestaltOrg.deleteOrg(newOrg.id)) must beTrue
    }

  }

  step({
    server.stop()
  })
}
