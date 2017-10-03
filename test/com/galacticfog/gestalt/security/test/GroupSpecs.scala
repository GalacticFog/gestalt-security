package com.galacticfog.gestalt.security.test

import java.util.UUID

import com.galacticfog.gestalt.security.api._
import com.galacticfog.gestalt.security.api.errors.{BadRequestException, ConflictException, ResourceNotFoundException}
import com.galacticfog.gestalt.security.data.domain.DirectoryFactory
import com.galacticfog.gestalt.security.data.model.UserGroupRepository
import com.galacticfog.gestalt.security.api.json.JsonImports._
import com.galacticfog.gestalt.security.data.APIConversions._
import play.api.libs.json.Json

class GroupSpecs extends SpecWithSDK {

  "Org Groups" should {

    lazy val newOrgName = "new-org-for-org-group-testing"
    lazy val newOrg = await(rootOrg.createSubOrg(GestaltOrgCreate(
      name = newOrgName,
      createDefaultUserGroup = true
    )))
    lazy val newOrgApp = await(newOrg.getServiceApp())
    lazy val unmappedGrpFromRootDir = await(rootDir.createGroup(GestaltGroupCreate(
      name = "unmapped-group-in-root-dir"
    )))

    "precheck" in {
      await(rootDir.getGroupByName(unmappedGrpFromRootDir.name)) must beSome(unmappedGrpFromRootDir)
      await(GestaltGroup.getById(unmappedGrpFromRootDir.id)) must beSome(unmappedGrpFromRootDir)
      await(rootDir.listGroups()) must contain(unmappedGrpFromRootDir)
    }

    "list an unmapped group in the org" in {
      // the creator must belong to the new org, so an admin group is created in the parent directory and mapped into the org.
      // this group is a sibling with unmappedGrpFromRootDir, hence the following
      await(newOrg.listGroups()) must contain(unmappedGrpFromRootDir)
    }

    "not list an unmapped group in the service app" in {
      await(newOrgApp.listGroups()) must contain(unmappedGrpFromRootDir)
    }

    "fail to get an unmapped group by id in the org" in {
      await(newOrg.getGroupById(unmappedGrpFromRootDir.id)) must beNone
    }

    "fail to get an unmapped group by id in the service app" in {
      await(newOrgApp.getGroupById(unmappedGrpFromRootDir.id)) must beNone
    }

    "fail to get an unmapped group by name in the org" in {
      await(newOrg.getGroupByName(unmappedGrpFromRootDir.name)) must beNone
    }

    "fail to get an unmapped group by name in the service app" in {
      await(newOrgApp.getGroupByName(unmappedGrpFromRootDir.name)) must beNone
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
      await(newOrg.listGroups()) must contain(unmappedGrpFromRootDir)
      await(newOrgApp.listGroups()) must contain(unmappedGrpFromRootDir)
      await(newOrg.getGroupById(unmappedGrpFromRootDir.id)) must beSome(unmappedGrpFromRootDir)
      await(newOrgApp.getGroupById(unmappedGrpFromRootDir.id)) must beSome(unmappedGrpFromRootDir)
      await(newOrg.getGroupByName(unmappedGrpFromRootDir.name)) must beNone // not in the default group store
      await(newOrgApp.getGroupByName(unmappedGrpFromRootDir.name)) must beNone // not in the default group store
      await(mapping.delete) must beTrue
      await(GestaltAccountStoreMapping.getById(mapping.id)) must beNone
      await(newOrg.listAccountStores) must not contain( (asm: GestaltAccountStoreMapping) => asm.id == mapping.id )
      await(newOrgApp.listAccountStores) must not contain( (asm: GestaltAccountStoreMapping) => asm.id == mapping.id )
    }

    "successfully list/get in the org/app after being mapped to the service app" in {
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
      await(newOrg.listGroups()) must contain(unmappedGrpFromRootDir)
      await(newOrgApp.listGroups()) must contain(unmappedGrpFromRootDir)
      await(newOrg.getGroupById(unmappedGrpFromRootDir.id)) must beSome(unmappedGrpFromRootDir)
      await(newOrgApp.getGroupById(unmappedGrpFromRootDir.id)) must beSome(unmappedGrpFromRootDir)
      await(newOrg.getGroupByName(unmappedGrpFromRootDir.name)) must beNone // not in the default group store
      await(newOrgApp.getGroupByName(unmappedGrpFromRootDir.name)) must beNone // not in the default group store
      await(mapping.delete) must beTrue
      await(GestaltAccountStoreMapping.getById(mapping.id)) must beNone
      await(newOrg.listAccountStores) must not contain( (asm: GestaltAccountStoreMapping) => asm.id == mapping.id )
      await(newOrgApp.listAccountStores) must not contain( (asm: GestaltAccountStoreMapping) => asm.id == mapping.id )
    }

    lazy val newOrgGrp = await(GestaltOrg.createGroup(newOrg.id, GestaltGroupCreateWithRights(
      name = "new-org-group",
      rights = Some(Seq(GestaltGrantCreate(
        grantName = "grantA"
      ), GestaltGrantCreate(
        grantName = "grantB", grantValue = Some("grantBvalue")
      )))
    )))

    "show up in org after creation" in {
      await(newOrg.getGroupById(newOrgGrp.id)) must beSome(newOrgGrp)
      await(newOrg.getGroupByName(newOrgGrp.name)) must beSome(newOrgGrp)
      await(newOrg.listGroups()) must contain(newOrgGrp)
    }

    "list grants provided at creation" in {
      await(GestaltOrg.listGroupGrants(newOrg.id, newOrgGrp.id)) must containTheSameElementsAs(Seq(
        GestaltRightGrant(null, "grantA", None, newOrgApp.id),
        GestaltRightGrant(null, "grantB", Some("grantBvalue"), newOrgApp.id)
      ), (a: GestaltRightGrant,b: GestaltRightGrant) => a.grantName == b.grantName && a.grantValue == b.grantValue && a.appId == b.appId)
    }

    lazy val newAcct1 = await(GestaltOrg.createAccount(newOrg.id, GestaltAccountCreateWithRights(
      username = "add-account-1", firstName = "", lastName = "", credential = GestaltPasswordCredential("")
    )))
    lazy val newAcct2 = await(GestaltOrg.createAccount(newOrg.id, GestaltAccountCreateWithRights(
      username = "add-account-2", firstName = "", lastName = "", credential = GestaltPasswordCredential("")
    )))
    lazy val newAcct3 = await(GestaltOrg.createAccount(newOrg.id, GestaltAccountCreateWithRights(
      username = "add-account-3", firstName = "", lastName = "", credential = GestaltPasswordCredential("")
    )))
    lazy val newAcct4 = await(GestaltOrg.createAccount(newOrg.id, GestaltAccountCreateWithRights(
      username = "add-account-4", firstName = "", lastName = "", credential = GestaltPasswordCredential("")
    )))

    "allow multiple simultaneous account additions" in {
      val newOrgGrp = await(GestaltOrg.createGroup(newOrg.id, GestaltGroupCreateWithRights("multiple-adds")))
      val newAccts1 = await(newOrgGrp.updateMembership(
        add = Seq(newAcct1.id, newAcct2.id),
        remove = Nil
      ))
      newAccts1 must containTheSameElementsAs( Seq(newAcct1.getLink, newAcct2.getLink) )
      val newAccts2 = await(newOrgGrp.updateMembership(
        add = Seq(newAcct3.id, newAcct4.id),
        remove = Seq(newAcct1.id, newAcct2.id)
      ))
      newAccts2 must containTheSameElementsAs( Seq(newAcct3.getLink, newAcct4.getLink) )
    }

    "throw ConflictException on duplicate group name" in {
      val _ = await(GestaltOrg.createGroup(newOrg.id, GestaltGroupCreateWithRights("dupe-group-name")))
      await(GestaltOrg.createGroup(newOrg.id, GestaltGroupCreateWithRights("dupe-group-name"))) must
        throwA[ConflictException](".*group name already exists in directory.*")
    }

    "properly handle bad account UUID when adding multiple accounts to a group" in {
      val newOrgGrp = await(GestaltOrg.createGroup(newOrg.id, GestaltGroupCreateWithRights("bad-account-add")))
      val members_before = await(newOrgGrp.updateMembership(
        add = Seq(newAcct1.id),
        remove = Nil
      ))
      await(newOrgGrp.updateMembership(
        add = Seq(newAcct2.id, UUID.randomUUID(), newAcct4.id),
        remove = Nil
      )) must throwA[BadRequestException](".*cannot add non-existent account to group.*")
      val members_after = await(newOrgGrp.listAccounts()) map {_.getLink}
      members_after must containTheSameElementsAs(members_before)
    }

    "properly handle bad group UUID when adding/removing accounts in a group" in {
      await(GestaltGroup.updateMembership(
        groupId = UUID.randomUUID(),
        add = Seq(newAcct3.id, newAcct4.id),
        remove = Nil
      )) must throwA[ResourceNotFoundException](".*could not locate requested group.*")
    }

    "update group properties using SDK object" in {
      val newGroup = await(GestaltOrg.createGroup(newOrg.id, GestaltGroupCreateWithRights("group-for-sdk-update")))
      val updated = await(GestaltGroup.updateGroup(newGroup.id, GestaltGroupUpdate(
        name = Some("updated-group-name"),
        description = Some("updated group description"))
      ))
      updated.id must_== newGroup.id
      updated.name must_== "updated-group-name"
      updated.description must beSome("updated group description")
      UserGroupRepository.find(updated.id) map {g => g: GestaltGroup} must beSome(updated)
    }

    "update group properties using SDK object" in {
      val newGroup = await(GestaltOrg.createGroup(newOrg.id, GestaltGroupCreateWithRights("group-for-patch-update")))
      val updated = await(newGroup.update(
        'name -> Json.toJson("patched-group-name"),
        'description -> Json.toJson("patched group description")
      ))
      updated.id must_== newGroup.id
      updated.name must_== "patched-group-name"
      updated.description must beSome("patched group description")
      UserGroupRepository.find(updated.id) map {g => g: GestaltGroup} must beSome(updated)
    }

  }

  step({
    server.stop()
  })
}
