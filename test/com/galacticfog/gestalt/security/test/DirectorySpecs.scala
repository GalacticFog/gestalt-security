package com.galacticfog.gestalt.security.test

import java.util.UUID

import com.galacticfog.gestalt.security.api._
import com.galacticfog.gestalt.security.api.errors.{BadRequestException, ConflictException}
import com.galacticfog.gestalt.security.api.json.JsonImports
import com.galacticfog.gestalt.security.data.domain.{AccountFactory, DirectoryFactory}
import play.api.libs.json.Json

class DirectorySpecs extends SpecWithSDK {

  lazy val testGroup2 = await(rootDir.getGroupByName("testGroup2")).get
  lazy val testUser2 = await(rootDir.getAccountByUsername("testAccount2")).get

  "Directory" should {

    "allow a new group to be created" in {
      await(rootDir.createGroup(GestaltGroupCreate("testGroup2",Some("some group called testGroup2")))) must haveName("testGroup2")
    }

    "create group with specified description" in {
      testGroup2.description must beSome("some group called testGroup2")
    }

    "allow a new user to be created in the group" in {
      await(rootDir.createAccount(GestaltAccountCreate(
        username = "testAccount2",
        firstName = "test",
        lastName = "account2",
        groups = Some(Seq(testGroup2.id)),
        credential = GestaltPasswordCredential(password = "letmein")
      ))) must haveName("testAccount2")
    }

    "list the group in the account memberships" in {
      val grp = await(GestaltGroup.getById(testGroup2.id)).get
      await(testUser2.listGroupMemberships()) must containTheSameElementsAs(Seq(grp))
    }

    "allow accounts to be added to groups after creation" in {
      val testGroup3 = await(rootDir.createGroup(GestaltGroupCreate("testGroup3")))
      val newMemberships = await(testGroup3.updateMembership(add = Seq(testUser2.id), remove = Seq()))
      newMemberships must contain(exactly(testUser2.getLink()))
      await(testUser2.listGroupMemberships()).map(_.id) must containTheSameElementsAs(
        Seq(testGroup2.id, testGroup3.id)
      )
    }

    "process group deletion" in {
      await(GestaltGroup.deleteGroup(testGroup2.id)) must beTrue
      await(GestaltGroup.getById(testGroup2.id)) must beNone
      await(rootDir.getGroupByName("testGroup2")) must beNone
      await(testUser2.listGroupMemberships()) must not contain hasName("testGroup2")
    }

    "process account deletion" in {
      await(GestaltAccount.deleteAccount(testUser2.id)) must beTrue
      AccountFactory.find(testUser2.id) must beNone
      await(GestaltAccount.getById(testUser2.id)) must beNone
      await(rootDir.getAccountByUsername("testAccount2")) must beNone
    }

    "return 200/false when deleting deleted accounts" in {
      // was deleted above
      await(GestaltAccount.deleteAccount(testUser2.id)) must beFalse
    }

    "return 200/false when deleting non-existent accounts" in {
      await(GestaltAccount.deleteAccount(UUID.randomUUID())) must beFalse
    }

  }

  lazy val newOrgName = "new-org-for-directory-testing"
  lazy val newOrg = await(rootOrg.createSubOrg(GestaltOrgCreate(
    name = newOrgName,
    createDefaultUserGroup = false
  )))
  lazy val newOrgApp = await(newOrg.getServiceApp())

  "Directory creation" should {

    lazy val testDir = await(newOrg.createDirectory(GestaltDirectoryCreate(
      "test-dir", DIRECTORY_TYPE_INTERNAL
    )))

    "prohibit duplicate names" in {
      await(newOrg.createDirectory(GestaltDirectoryCreate(
        testDir.name, DIRECTORY_TYPE_INTERNAL
      ))) must throwA[ConflictException](".*name already exists in org.*")
    }

    "require valid directory type" in {
      // sdk doesn't allow raw string to be passed, so we'll bypass
      // this actually presents as a parse error now
      import JsonImports._
      await(keySdk.post[GestaltDirectory](s"orgs/${newOrg.id}/directories",Json.obj(
        "name" -> "bad-directory",
        "directoryType" -> "badtype"
      ))) must throwA[BadRequestException](".*invalid payload.*")
    }

    "not automatically map the directory" in {
      await(newOrg.listAccountStores()) must not contain(
        (asm: GestaltAccountStoreMapping) =>  asm.storeId == testDir.id
      )
    }

    "not allow unmapped dir accounts to auth" in {
      val d = DirectoryFactory.find(testDir.id).get
      val a = d.createAccount("test-account", None, "", "", None, None, GestaltPasswordCredential("test-password"))
      a must beSuccessfulTry
      val token = await(GestaltToken.grantPasswordToken(newOrg.id, "test-account", "test-password"))
      token must beNone
    }

  }

  step({
    server.stop()
  })
}
