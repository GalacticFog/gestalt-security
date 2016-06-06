package com.galacticfog.gestalt.security.test

import java.util.UUID

import com.galacticfog.gestalt.security.api._
import com.galacticfog.gestalt.security.api.errors.{ResourceNotFoundException, UnauthorizedAPIException, BadRequestException, ConflictException}
import com.galacticfog.gestalt.security.api.GestaltOrg

class OrgSpecs extends SpecWithSDK {

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
      await(rootOrg.listSubOrgs).map(_.id) must containTheSameElementsAs(Seq(newOrg.id))
    }

    "return its created description" in {
      await(GestaltOrg.getById(newOrg.id)).get.description must beSome(newOrgDesc)
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
      val rootGroupSeq = await(newOrg.listGroups()).filter(_.directory.id == rootDir.id)
      rootGroupSeq must haveSize(1)
      val rootGroup = rootGroupSeq.head
      await(newOrg.getGroupById(rootGroup.id)) must beSome(rootGroup)
      await(newOrg.getGroupByName(rootGroup.name)) must beNone
    }

    "should list groups created directly in the directory" in {
      val manualGrp = await(newOrgDir.createGroup(GestaltGroupCreate(
        name = "manually-created-dir-group"
      )))
      await(newOrg.listGroups()) must contain(manualGrp)
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
      await(newOrg.listAccounts()) must contain(manualAccount)
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
      await(newOrg.listAccounts()) must not contain((a: GestaltAccount) => a.username == failAccountName)
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
      await(newOrg.listAccounts()) must not contain((a: GestaltAccount) => a.username == failAccountName)
    }

    "should not add group with invalid right grants" in {
      val failGroupName = "failedGroup"
      await(GestaltOrg.createGroup( newOrg.id, GestaltGroupCreateWithRights(
        name = failGroupName,
        rights = Some(Seq(GestaltGrantCreate(
          grantName = "" // fail
        )))
      ))) must throwA[BadRequestException](".*right grant must be non-empty without leading or trailing spaces.*")
      await(newOrg.listGroups()) must not contain((g: GestaltGroup) => g.name == failGroupName)
    }

    "should create new groups in the new directory owned by the org" in {
      val newGroup = await(GestaltOrg.createGroup( newOrg.id, GestaltGroupCreateWithRights(
        name = "group-should-belong-to-new-org"
      )))
      newGroup.directory.orgId must_== newOrg.id
    }

    lazy val grp1 = await(GestaltOrg.createGroup(newOrg.id, GestaltGroupCreateWithRights("query-group-1")))
    lazy val grp2 = await(GestaltOrg.createGroup(newOrg.id, GestaltGroupCreateWithRights("query-group-2")))
    lazy val grp3 = await(GestaltOrg.createGroup(newOrg.id, GestaltGroupCreateWithRights("query-group-3")))

    "list groups with name query strings" in {
      await(newOrg.listGroups( "name" -> "query-group-*" )) must containTheSameElementsAs(Seq(grp1, grp2, grp3))
      await(newOrg.listGroups( "name" -> grp2.name )) must containTheSameElementsAs(Seq(grp2))
      await(newOrg.listGroups( "name" -> "*-3" )) must containTheSameElementsAs(Seq(grp3))
      await(newOrg.listGroups( "name" -> "*-group-*" )) must containTheSameElementsAs(Seq(grp1,grp2,grp3))
    }

  }

  val subOrgName = "suborg-testing"
  lazy val subOrg = await(rootOrg.createSubOrg(GestaltOrgCreate(
    name = subOrgName,
    createDefaultUserGroup = false
  )))
  val subSubOrgName = "suborg-testing"
  lazy val subSubOrg = await(subOrg.createSubOrg(GestaltOrgCreate(
    name = subSubOrgName,
    createDefaultUserGroup = false
  )))

  "Orgs" should {

    "not show up in their own child org listing" in {
      await(rootOrg.listSubOrgs()) should not contain(
        (o: GestaltOrg) => o.id == rootOrg.id
      )
      await(subOrg.listSubOrgs()) should not contain(
        (o: GestaltOrg) => o.id == subOrg.id
      )
      await(subSubOrg.listSubOrgs()) should not contain(
        (o: GestaltOrg) =>  o.id == subSubOrg.id
      )
    }

  }

  "FQON routing" should {

    "return 401 for invalid fqon on unauthenticated requests" in {
      await(anonSdk.getJson("bad-org")) must throwA[UnauthorizedAPIException]
    }

    "return 404 for invalid fqon on authenticated request with fqon path" in {
      await(keySdk.getJson("bad-org")) must throwA[ResourceNotFoundException].like {
        case e: ResourceNotFoundException => e.resource must_== "/bad-org"
      }
    }

    "return 404 for valid fqon on authenticated request with fqon path" in {
      val badUrl = "/bad-url/acccounts"
      await(keySdk.getJson(badUrl)) must throwA[ResourceNotFoundException].like {
        case e: ResourceNotFoundException => e.resource must_== badUrl
      }
    }

  }

  step({
    server.stop()
  })
}
