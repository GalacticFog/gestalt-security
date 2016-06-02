package com.galacticfog.gestalt.security.test

import java.util.UUID

import com.galacticfog.gestalt.security.api.{GestaltOrg, _}
import com.galacticfog.gestalt.security.api.errors.{BadRequestException, ConflictException}
import com.galacticfog.gestalt.security.api.json.JsonImports._
import com.galacticfog.gestalt.security.data.APIConversions._
import com.galacticfog.gestalt.security.data.domain._

class AccountStoreSpecs extends SpecWithSDK {

  "Account Store Mappings" should {

    lazy val testDirInRootOrg = await(rootOrg.createDirectory(GestaltDirectoryCreate(
      name = "test-dir-in-root-org",
      directoryType = DIRECTORY_TYPE_INTERNAL,
      description = None,
      config = None
    )))
    lazy val testGroupInTestDir = await(testDirInRootOrg.createGroup(GestaltGroupCreate(
      name = "test-group-in-test-dir"
    )))
    lazy val testGroup2InTestDir = await(testDirInRootOrg.createGroup(GestaltGroupCreate(
      name = "test-group2-in-test-dir"
    )))
    lazy val testSubOrg = await(rootOrg.createSubOrg(GestaltOrgCreate(
      name = "suborg-for-asm-testing",
      createDefaultUserGroup = false
    )))
    lazy val testSubOrgApp = await(testSubOrg.getServiceApp)

    "fail appropriately for non-existent directory store on org" in {
      await(testSubOrg.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "failed-mapping",
        description = None,
        storeType = DIRECTORY,
        accountStoreId = UUID.randomUUID(),
      isDefaultAccountStore = false,
      isDefaultGroupStore = false
      ))) must throwA[BadRequestException](".*account store does not correspond to an existing directory.*")
    }

    "fail appropriately for non-existent directory store on app" in {
      await(testSubOrgApp.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "failed-mapping",
        description = None,
        storeType = DIRECTORY,
        accountStoreId = UUID.randomUUID(),
        isDefaultAccountStore = false,
        isDefaultGroupStore = false
      ))) must throwA[BadRequestException](".*account store does not correspond to an existing directory.*")
    }

    "fail appropriately for non-existent group store on org" in {
      await(testSubOrg.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "failed-mapping",
        description = None,
        storeType = GROUP,
        accountStoreId = UUID.randomUUID(),
        isDefaultAccountStore = false,
        isDefaultGroupStore = false
      ))) must throwA[BadRequestException](".*account store does not correspond to an existing group.*")
    }

    "fail appropriately for non-existent group store on app" in {
      await(testSubOrgApp.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "failed-mapping",
        description = None,
        storeType = GROUP,
        accountStoreId = UUID.randomUUID(),
        isDefaultAccountStore = false,
        isDefaultGroupStore = false
      ))) must throwA[BadRequestException](".*account store does not correspond to an existing group.*")
    }

    "fail appropriately when setting group as default group store in org" in {
      await(testSubOrg.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "failed-mapping",
        description = None,
        storeType = GROUP,
        accountStoreId = testGroupInTestDir.id,
        isDefaultAccountStore = false,
        isDefaultGroupStore = true
      ))) must throwA[BadRequestException](".*default group store must be an account store of type DIRECTORY.*")
    }

    "fail appropriately when setting group as default group store in app" in {
      await(testSubOrgApp.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "failed-mapping",
        description = None,
        storeType = GROUP,
        accountStoreId = testGroupInTestDir.id,
        isDefaultAccountStore = false,
        isDefaultGroupStore = true
      ))) must throwA[BadRequestException](".*default group store must be an account store of type DIRECTORY.*")
    }

    "fail appropriately for redundant dir mapping against org" in {
      val newMapping = await(testSubOrg.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "first-mapping",
        description = None,
        storeType = DIRECTORY,
        accountStoreId = testDirInRootOrg.id,
        isDefaultAccountStore = false,
        isDefaultGroupStore = false
      )))
      await(testSubOrg.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "second-mapping",
        description = None,
        storeType = DIRECTORY,
        accountStoreId = testDirInRootOrg.id,
        isDefaultAccountStore = false,
        isDefaultGroupStore = false
      ))) must throwA[ConflictException](".*mapping already exists.*")
      await(GestaltAccountStoreMapping.delete(newMapping.id)) must beTrue
    }

    "fail appropriately for redundant dir mapping against app" in {
      val newMapping = await(testSubOrgApp.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "first-mapping",
        description = None,
        storeType = DIRECTORY,
        accountStoreId = testDirInRootOrg.id,
        isDefaultAccountStore = false,
        isDefaultGroupStore = false
      )))
      await(testSubOrgApp.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "second-mapping",
        description = None,
        storeType = DIRECTORY,
        accountStoreId = testDirInRootOrg.id,
        isDefaultAccountStore = false,
        isDefaultGroupStore = false
      ))) must throwA[ConflictException](".*mapping already exists.*")
      await(GestaltAccountStoreMapping.delete(newMapping.id)) must beTrue
    }

    "fail appropriately for redundant group mapping against org" in {
      val newMapping = await(testSubOrg.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "first-mapping",
        description = None,
        storeType = GROUP,
        accountStoreId = testGroupInTestDir.id,
        isDefaultAccountStore = false,
        isDefaultGroupStore = false
      )))
      await(testSubOrg.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "second-mapping",
        description = None,
        storeType = GROUP,
        accountStoreId = testGroupInTestDir.id,
        isDefaultAccountStore = false,
        isDefaultGroupStore = false
      ))) must throwA[ConflictException](".*mapping already exists.*")
      await(GestaltAccountStoreMapping.delete(newMapping.id)) must beTrue
    }

    "fail appropriately for redundant group mapping against app" in {
      val newMapping = await(testSubOrgApp.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "first-mapping",
        description = None,
        storeType = GROUP,
        accountStoreId = testGroupInTestDir.id,
        isDefaultAccountStore = false,
        isDefaultGroupStore = false
      )))
      await(testSubOrgApp.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "second-mapping",
        description = None,
        storeType = GROUP,
        accountStoreId = testGroupInTestDir.id,
        isDefaultAccountStore = false,
        isDefaultGroupStore = false
      ))) must throwA[ConflictException](".*mapping already exists.*")
      await(GestaltAccountStoreMapping.delete(newMapping.id)) must beTrue
    }

    "fail appropriately when setting conflicting default account store on app" in {
      val firstMapping = await(testSubOrgApp.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "first-default-account-store-mapping",
        description = None,
        storeType = GROUP,
        accountStoreId = testGroupInTestDir.id,
        isDefaultAccountStore = true,
        isDefaultGroupStore = false
      )))
      await(testSubOrgApp.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "conflicting-default-account-store-mapping",
        description = None,
        storeType = GROUP,
        accountStoreId = testGroup2InTestDir.id,
        isDefaultAccountStore = true,
        isDefaultGroupStore = false
      ))) must throwA[ConflictException](".*default account store already set.*")
      await(GestaltAccountStoreMapping.delete(firstMapping.id)) must beTrue
      await(testSubOrgApp.listAccountStores) must not contain(
        (asm: GestaltAccountStoreMapping) => asm.storeId == testGroup2InTestDir.id
      )
    }

    "fail appropriately when setting conflicting default account store on org" in {
      val firstMapping = await(testSubOrg.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "first-default-account-store-mapping",
        description = None,
        storeType = GROUP,
        accountStoreId = testGroupInTestDir.id,
        isDefaultAccountStore = true,
        isDefaultGroupStore = false
      )))
      await(testSubOrg.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "conflicting-default-account-store-mapping",
        description = None,
        storeType = GROUP,
        accountStoreId = testGroup2InTestDir.id,
        isDefaultAccountStore = true,
        isDefaultGroupStore = false
      ))) must throwA[ConflictException](".*default account store already set.*")
      await(GestaltAccountStoreMapping.delete(firstMapping.id)) must beTrue
      await(testSubOrgApp.listAccountStores) must not contain(
        (asm: GestaltAccountStoreMapping) => asm.storeId == testGroup2InTestDir.id
      )
    }

    "fail appropriately when setting conflicting default group store on org" in {
      val firstMapping = await(testSubOrg.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "first-default-group-store-mapping",
        description = None,
        storeType = DIRECTORY,
        accountStoreId = testDirInRootOrg.id,
        isDefaultAccountStore = false,
        isDefaultGroupStore = true
      )))
      await(testSubOrg.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "conflicting-default-group-store-mapping",
        description = None,
        storeType = DIRECTORY,
        accountStoreId = rootDir.id,
        isDefaultAccountStore = false,
        isDefaultGroupStore = true
      ))) must throwA[ConflictException](".*default group store already set.*")
      await(GestaltAccountStoreMapping.delete(firstMapping.id)) must beTrue
      await(testSubOrgApp.listAccountStores) must not contain(
        (asm: GestaltAccountStoreMapping) => asm.storeId == testGroup2InTestDir.id
      )
    }

    "fail appropriately when setting conflicting default group store on app" in {
      val firstMapping = await(testSubOrgApp.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "first-default-group-store-mapping",
        description = None,
        storeType = DIRECTORY,
        accountStoreId = testDirInRootOrg.id,
        isDefaultAccountStore = false,
        isDefaultGroupStore = true
      )))
      await(testSubOrgApp.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "conflicting-default-group-store-mapping",
        description = None,
        storeType = DIRECTORY,
        accountStoreId = rootDir.id,
        isDefaultAccountStore = false,
        isDefaultGroupStore = true
      ))) must throwA[ConflictException](".*default group store already set.*")
      await(GestaltAccountStoreMapping.delete(firstMapping.id)) must beTrue
      await(testSubOrgApp.listAccountStores) must not contain(
        (asm: GestaltAccountStoreMapping) => asm.storeId == testGroup2InTestDir.id
      )
    }

    "cleanup" in {
      await(GestaltDirectory.deleteDirectory(testDirInRootOrg.id)) must beTrue
      await(GestaltOrg.deleteOrg(testSubOrg.id)) must beTrue
    }

  }

  step({
    server.stop()
  })
}
