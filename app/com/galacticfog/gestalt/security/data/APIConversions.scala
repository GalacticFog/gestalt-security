package com.galacticfog.gestalt.security.data

import java.util.UUID

import com.galacticfog.gestalt.security.api._
import com.galacticfog.gestalt.security.data.domain.OrgFactory
import com.galacticfog.gestalt.security.data.model._

object APIConversions {
  implicit def accountModelToApi(uar: UserAccountRepository): GestaltAccount = {
    GestaltAccount(
      id = uar.id.asInstanceOf[UUID],
      username = uar.username,
      firstName = uar.firstName,
      lastName = uar.lastName,
      email = uar.email,
      phoneNumber  = uar.phoneNumber getOrElse "",
      directoryId = uar.dirId.asInstanceOf[UUID]
    )
  }

  implicit def groupModelToApi(ugr: UserGroupRepository): GestaltGroup = {
    GestaltGroup(
      id = ugr.id.asInstanceOf[UUID],
      name = ugr.name,
      directoryId = ugr.dirId.asInstanceOf[UUID],
      disabled = ugr.disabled
    )
  }

  implicit def rightModelToApi(right: RightGrantRepository): GestaltRightGrant = {
    GestaltRightGrant(
      id = right.grantId.asInstanceOf[UUID],
      grantName = right.grantName,
      grantValue = right.grantValue,
      appId = right.appId.asInstanceOf[UUID]
    )
  }

  implicit def orgModelToApi(org: GestaltOrgRepository): GestaltOrg = {
    GestaltOrg(
      id = org.id.asInstanceOf[UUID],
      name = org.name,
      fqon = org.fqon,
      parent = org.parent.flatMap{pid => OrgFactory.findByOrgId(pid.asInstanceOf[UUID])}.map {p =>
        ResourceLink(
          id = p.id.asInstanceOf[UUID],
          name = p.name,
          href = s"/orgs/${p.id}",
          properties = None
        )
      },
      children = OrgFactory.getChildren(org.id.asInstanceOf[UUID]).map {
        c => ResourceLink(
          id = c.id.asInstanceOf[UUID],
          name = c.fqon,
          href = s"/orgs/${c.id}",
          properties = None
        )
      }
    )
  }

  implicit def appModelToApi(app: GestaltAppRepository): GestaltApp = {
    GestaltApp(
      id = app.id.asInstanceOf[UUID],
      name = app.name,
      orgId = app.orgId.asInstanceOf[UUID],
      isServiceApp = app.serviceOrgId.isDefined
    )
  }

  implicit def dirModelToApi(dir: GestaltDirectoryRepository): GestaltDirectory = {
    GestaltDirectory(
      id = dir.id.asInstanceOf[UUID],
      name = dir.name,
      description = dir.description getOrElse "",
      orgId = dir.orgId.asInstanceOf[UUID]
    )
  }
}
