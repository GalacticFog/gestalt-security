package com.galacticfog.gestalt.security.test

import com.galacticfog.gestalt.security.api._

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
      await(testUser2.listGroupMemberships()) must not contain(hasName("testGroup2"))
    }

    "process account deletion" in {
      await(GestaltAccount.deleteAccount(testUser2.id)) must beTrue
      await(GestaltAccount.getById(testUser2.id)) must beSome(testUser2)
      await(rootDir.getAccountByUsername("testAccount2")) must beSome(testUser2)
    }

  }

  step({
    server.stop()
  })
}
