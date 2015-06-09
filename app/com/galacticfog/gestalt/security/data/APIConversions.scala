package com.galacticfog.gestalt.security.data

import com.galacticfog.gestalt.security.api.{GestaltApp, GestaltOrg, GestaltRightGrant, GestaltAccount}
import com.galacticfog.gestalt.security.data.domain.GestaltOrgFactory
import com.galacticfog.gestalt.security.data.model.{AppRepository, GestaltOrgRepository, RightGrantRepository, UserAccountRepository}

object APIConversions {
  implicit def accountModelToApi(uar: UserAccountRepository): GestaltAccount = {
    GestaltAccount(
      username = uar.username,
      firstName = uar.firstName,
      lastName = uar.lastName,
      email = uar.email
    )
  }

  implicit def rightModelToApi(right: RightGrantRepository): GestaltRightGrant = {
    GestaltRightGrant(
      grantName = right.grantName,
      grantValue = right.grantValue
    )
  }

  implicit def orgModelToApi(org: GestaltOrgRepository): GestaltOrg = {
    GestaltOrg(
      orgId = org.orgId,
      orgName = org.orgName
    )
  }

  implicit def appModelToApi(app: AppRepository): GestaltApp = {
    GestaltApp(
      appId = app.appId,
      appName = app.appName,
      org = GestaltOrgFactory.findByOrgId(app.orgId).get
    )
  }
}
