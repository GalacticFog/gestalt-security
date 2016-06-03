package com.galacticfog.gestalt.security.test

import com.galacticfog.gestalt.security.api._

class OrgAccountSpecs extends SpecWithSDK {

  "Org Accounts" should {

    lazy val newOrgName = "new-org-for-org-account-testing"
    lazy val newOrg = await(rootOrg.createSubOrg(GestaltOrgCreate(
      name = newOrgName,
      createDefaultUserGroup = true
    )))
    lazy val newOrgApp = await(newOrg.getServiceApp())
    lazy val newOrgDir = await(newOrg.listDirectories()).head
    lazy val unmappedGrpFromRootDir = await(rootDir.createGroup(GestaltGroupCreate(
      name = "unmapped-group-in-root-dir"
    )))
    lazy val unmappedActFromRootDir = await(rootDir.createAccount(GestaltAccountCreate(
      username = "unmapped",
      firstName = "Bob",
      lastName = "Not-Mapped",
      groups = Some(Seq(unmappedGrpFromRootDir.id)), // so we can map it below
      credential = GestaltPasswordCredential("letmein")
    )))

    "precheck" in {
      await(rootDir.getGroupByName(unmappedGrpFromRootDir.name)) must beSome(unmappedGrpFromRootDir)
      await(GestaltGroup.getById(unmappedGrpFromRootDir.id)) must beSome(unmappedGrpFromRootDir)
      await(rootDir.listGroups()) must contain(unmappedGrpFromRootDir)
      await(rootDir.getAccountByUsername(unmappedActFromRootDir.username)) must beSome(unmappedActFromRootDir)
      await(GestaltAccount.getById(unmappedActFromRootDir.id)) must beSome(unmappedActFromRootDir)
      await(rootDir.listAccounts()) must contain(unmappedActFromRootDir)
      await(unmappedGrpFromRootDir.listAccounts()) must containTheSameElementsAs(Seq(unmappedActFromRootDir))
    }

    "not list an unmapped account in the org" in {
      await(newOrg.listAccounts) must not contain unmappedActFromRootDir
    }

    "not list an unmapped account in the service app" in {
      await(newOrgApp.listAccounts) must not contain unmappedActFromRootDir
    }

    "fail to get an unmapped account by id in the org" in {
      await(newOrg.getAccountById(unmappedActFromRootDir.id)) must beNone
    }

    "fail to get an unmapped account by id in the service app" in {
      await(newOrgApp.getAccountById(unmappedActFromRootDir.id)) must beNone
    }

    "fail to get an unmapped account by name in the org" in {
      await(newOrg.getAccountByUsername(unmappedActFromRootDir.name)) must beNone
    }

    "fail to get an unmapped account by name in the service app" in {
      await(newOrgApp.getAccountByUsername(unmappedActFromRootDir.name)) must beNone
    }

    "successfully list/get in the org/app after being mapped to the org" in {
      val mapping = await(newOrg.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "test-mapping",
        description = None,
        storeType = GROUP,
        accountStoreId = unmappedGrpFromRootDir.id,
        isDefaultAccountStore = false,
        isDefaultGroupStore = false
      )))
      await(newOrg.listAccountStores) must contain( (asm: GestaltAccountStoreMapping) => asm.id == mapping.id )
      await(newOrgApp.listAccountStores) must contain( (asm: GestaltAccountStoreMapping) => asm.id == mapping.id )
      await(newOrg.listAccounts) must contain(unmappedActFromRootDir)
      await(newOrgApp.listAccounts) must contain(unmappedActFromRootDir)
      await(newOrg.getAccountById(unmappedActFromRootDir.id)) must beSome(unmappedActFromRootDir)
      await(newOrgApp.getAccountById(unmappedActFromRootDir.id)) must beSome(unmappedActFromRootDir)
      await(newOrg.getAccountByUsername(unmappedActFromRootDir.name)) must beNone // because not default dir
      await(newOrgApp.getAccountByUsername(unmappedActFromRootDir.name)) must beNone // because not in default dir
      await(mapping.delete) must beTrue
      await(GestaltAccountStoreMapping.getById(mapping.id)) must beNone
      await(newOrg.listAccountStores) must not contain( (asm: GestaltAccountStoreMapping) => asm.id == mapping.id )
      await(newOrgApp.listAccountStores) must not contain( (asm: GestaltAccountStoreMapping) => asm.id == mapping.id )
    }

    "successfully list/get in the org/app after being mapped to the org app" in {
      val mapping = await(newOrgApp.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "test-mapping",
        description = None,
        storeType = GROUP,
        accountStoreId = unmappedGrpFromRootDir.id,
        isDefaultAccountStore = false,
        isDefaultGroupStore = false
      )))
      await(newOrg.listAccountStores) must contain( (asm: GestaltAccountStoreMapping) => asm.id == mapping.id )
      await(newOrgApp.listAccountStores) must contain( (asm: GestaltAccountStoreMapping) => asm.id == mapping.id )
      await(newOrg.listAccounts) must contain(unmappedActFromRootDir)
      await(newOrgApp.listAccounts) must contain(unmappedActFromRootDir)
      await(newOrg.getAccountById(unmappedActFromRootDir.id)) must beSome(unmappedActFromRootDir)
      await(newOrgApp.getAccountById(unmappedActFromRootDir.id)) must beSome(unmappedActFromRootDir)
      await(newOrg.getAccountByUsername(unmappedActFromRootDir.name)) must beNone // because not default dir
      await(newOrgApp.getAccountByUsername(unmappedActFromRootDir.name)) must beNone // because not in default dir
      await(mapping.delete) must beTrue
      await(GestaltAccountStoreMapping.getById(mapping.id)) must beNone
      await(newOrg.listAccountStores) must not contain( (asm: GestaltAccountStoreMapping) => asm.id == mapping.id )
      await(newOrgApp.listAccountStores) must not contain( (asm: GestaltAccountStoreMapping) => asm.id == mapping.id )
    }

    lazy val newOrgAct = await(GestaltOrg.createAccount(newOrg.id, GestaltAccountCreateWithRights(
      username = "new-org-account",
      firstName = "Account",
      lastName = "InNewOrg",
      groups = None,
      rights = Some(Seq(GestaltGrantCreate(
        grantName = "grantA"
      ), GestaltGrantCreate(
        grantName = "grantB", grantValue = Some("grantBvalue")
      ))),
      credential = GestaltPasswordCredential("letmein")
    )))

    "show up in org after creation" in {
      await(newOrg.getAccountById(newOrgAct.id)) must beSome(newOrgAct)
      await(newOrg.getAccountByUsername(newOrgAct.name)) must beSome(newOrgAct)
      await(newOrg.listAccounts) must contain(newOrgAct)
    }

    "list grants provided at creation" in {
      await(GestaltOrg.listAccountGrants(newOrg.id, newOrgAct.id)) must containTheSameElementsAs(Seq(
        GestaltRightGrant(null, "grantA", None, newOrgApp.id),
        GestaltRightGrant(null, "grantB", Some("grantBvalue"), newOrgApp.id)
      ), (a: GestaltRightGrant,b: GestaltRightGrant) => (a.grantName == b.grantName && a.grantValue == b.grantValue && a.appId == b.appId))
    }

    "not include duplicates" in {
      val accounts = await(newOrg.listAccounts())
      accounts must containTheSameElementsAs(accounts.distinct)
    }

  }

  step({
    server.stop()
  })
}
