package com.galacticfog.gestalt.security.test

import com.galacticfog.gestalt.security.api._
import com.galacticfog.gestalt.security.api.errors.BadRequestException
import com.galacticfog.gestalt.security.data.APIConversions._
import com.galacticfog.gestalt.security.data.domain._
import com.galacticfog.gestalt.security.data.model.APICredentialRepository
import play.api.libs.json.Json

class AppSpecs extends SpecWithSDK {

  "Org Apps" should {

    val testAppName = "test-app-in-root-org"
    lazy val testApp = await(rootOrg.createApp(GestaltAppCreate(
      name = testAppName
    )))

    "be created properly under orgs" in {
      testApp.isServiceApp must beFalse
      testApp.name must_== testAppName
      testApp.orgId must_== rootOrg.id
    }

    "show up in org app listing after creation" in {
      await(rootOrg.listApps) must contain(testApp)
    }

    "be available by name under org" in {
      await(rootOrg.getAppByName(testAppName)) must beSome(testApp)
    }

    "be available by id" in {
      await(GestaltApp.getById(testApp.id)) must beSome(testApp)
    }

    "properly fail on account creation with no default account store" in {
      await(testApp.createAccount(GestaltAccountCreateWithRights(
        username = "wfail",
        firstName = "Will",
        lastName = "Fail",
        credential = GestaltPasswordCredential("letmein"),
        groups = None,
        rights = None
      ))) must throwA[BadRequestException](".*application does not have a default account store.*")
    }

    "properly fail on group creation with no default account store" in {
      await(testApp.createGroup(GestaltGroupCreateWithRights(
        name = "will-fail-group",
        rights = None
      ))) must throwA[BadRequestException](".*specified app does not have a default group store.*")
    }

    "be capable of deletion" in {
      await(GestaltApp.deleteApp(testApp.id)) must beTrue
    }

    "not show up after deletion in org app listing" in {
      await(rootOrg.listApps) must not contain testApp
    }

    "not be available after deletion by id" in {
      await(GestaltApp.getById(testApp.id)) must beNone
    }

  }

  step({
    server.stop()
  })
}
