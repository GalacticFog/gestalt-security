package com.galacticfog.gestalt.security.test

import java.util.UUID

import com.galacticfog.gestalt.security.Init
import com.galacticfog.gestalt.security.api._

class SyncSpec extends SpecWithSDK {

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

    "match admin in init" in {
      val admin = Init.getInitSettings.toOption.flatMap(_.rootAccount).map(_.asInstanceOf[UUID])
      sync.admin.map(_.id) must_== admin
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
