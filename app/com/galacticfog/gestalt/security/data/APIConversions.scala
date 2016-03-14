package com.galacticfog.gestalt.security.data

import java.util.UUID

import com.galacticfog.gestalt.security.api.GestaltToken.ACCESS_TOKEN
import com.galacticfog.gestalt.security.api._
import com.galacticfog.gestalt.security.data.domain.{DirectoryFactory, Directory, OrgFactory}
import com.galacticfog.gestalt.security.data.model._

object APIConversions {
  implicit def accountModelToApi(uar: UserAccountRepository): GestaltAccount = {
    GestaltAccount(
      id = uar.id.asInstanceOf[UUID],
      username = uar.username,
      firstName = uar.firstName,
      lastName = uar.lastName,
      email = uar.email getOrElse "",
      phoneNumber  = uar.phoneNumber getOrElse "",
      directory = DirectoryFactory.find(uar.dirId.asInstanceOf[UUID]).get
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

  implicit def dirModelToApi(dir: Directory): GestaltDirectory = {
    GestaltDirectory(
      id = dir.id,
      name = dir.name,
      description = dir.description getOrElse "",
      orgId = dir.orgId
    )
  }

  implicit def mappingModelToApi(asm: AccountStoreMappingRepository): GestaltAccountStoreMapping = {
    GestaltAccountStoreMapping(
      id = asm.id.asInstanceOf[UUID],
      name = asm.name getOrElse "",
      description = asm.description getOrElse s"mapping between app ${asm.appId} and ${asm.storeType} ${asm.accountStoreId}",
      storeType = if (asm.storeType == GROUP.label) GROUP else DIRECTORY,
      storeId = asm.accountStoreId.asInstanceOf[UUID],
      appId = asm.appId.asInstanceOf[UUID],
      isDefaultAccountStore = asm.defaultAccountStore.contains(asm.appId),
      isDefaultGroupStore = asm.defaultGroupStore.contains(asm.appId)
    )
  }

  implicit def tokenModelToApi(token: TokenRepository): GestaltToken = OpaqueToken(token.id.asInstanceOf[UUID], ACCESS_TOKEN)
}
