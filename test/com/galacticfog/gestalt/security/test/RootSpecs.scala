package com.galacticfog.gestalt.security.test

import com.galacticfog.gestalt.security.api._
import com.galacticfog.gestalt.security.api.errors.BadRequestException
import com.galacticfog.gestalt.security.data.APIConversions._
import com.galacticfog.gestalt.security.data.domain._

class RootSpecs extends SpecWithSDK {

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
      await(rootOrg.listAccounts()) must contain(exactly(rootAccount))
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
      await(rootApp.listAccounts()) must containTheSameElementsAs(await(rootOrg.listAccounts()))
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

  step({
    server.stop()
  })
}
