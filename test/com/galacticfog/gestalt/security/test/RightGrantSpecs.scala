package com.galacticfog.gestalt.security.test

import com.galacticfog.gestalt.security.api._
import com.galacticfog.gestalt.security.api.errors.BadRequestException

class RightGrantSpecs extends SpecWithSDK {

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

  step({
    server.stop()
  })
}
