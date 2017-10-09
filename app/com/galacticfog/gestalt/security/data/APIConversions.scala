package com.galacticfog.gestalt.security.data

import java.util.UUID

import com.galacticfog.gestalt.security.api.GestaltToken.ACCESS_TOKEN
import com.galacticfog.gestalt.security.api._
import com.galacticfog.gestalt.security.data.domain.{DirectoryFactory, GroupFactory, OrgFactory}
import com.galacticfog.gestalt.security.data.model._
import com.galacticfog.gestalt.security.plugins.{DirectoryPlugin, GroupMembership}
import play.api.libs.json.Json

import scala.util.Try

object APIConversions {
  implicit def accountModelToApi(uar: UserAccountRepository): GestaltAccount = {
    GestaltAccount(
      id = uar.id.asInstanceOf[UUID],
      username = uar.username,
      firstName = uar.firstName,
      lastName = uar.lastName,
      email = uar.email,
      phoneNumber  = uar.phoneNumber,
      directory = DirectoryFactory.find(uar.dirId.asInstanceOf[UUID]).get,
      description = uar.description
    )
  }

  implicit def groupModelToApi(ugr: UserGroupRepository): GestaltGroup = {
    GestaltGroup(
      id = ugr.id.asInstanceOf[UUID],
      name = ugr.name,
      directory = DirectoryFactory.find(ugr.dirId.asInstanceOf[UUID]).get,
      disabled = ugr.disabled,
      description = ugr.description,
      accounts = GroupFactory.listGroupAccounts(ugr.id.asInstanceOf[UUID]) map {_.getLink()}
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
      description = org.description,
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
      description = app.description,
      name = app.name,
      orgId = app.orgId.asInstanceOf[UUID],
      isServiceApp = app.serviceOrgId.isDefined
    )
  }

  implicit def dirModelToApi(dir: GestaltDirectoryRepository): GestaltDirectory = {
    GestaltDirectory(
      id = dir.id.asInstanceOf[UUID],
      name = dir.name,
      description = dir.description,
      orgId = dir.orgId.asInstanceOf[UUID],
      config = dir.config flatMap (json => Try{Json.parse(json)}.toOption)
    )
  }

  implicit def dirPluginToApi(dir: DirectoryPlugin): GestaltDirectory = {
    GestaltDirectory(
      id = dir.id,
      name = dir.name,
      description = dir.description,
      orgId = dir.orgId
    )
  }

  implicit def mappingModelToApi(asm: AccountStoreMappingRepository): GestaltAccountStoreMapping = {
    GestaltAccountStoreMapping(
      id = asm.id.asInstanceOf[UUID],
      name = asm.name getOrElse "",
      description = asm.description orElse Some(s"mapping between app ${asm.appId} and ${asm.storeType} ${asm.accountStoreId}"),
      storeType = if (asm.storeType == GROUP.label) GROUP else DIRECTORY,
      storeId = asm.accountStoreId.asInstanceOf[UUID],
      appId = asm.appId.asInstanceOf[UUID],
      isDefaultAccountStore = asm.defaultAccountStore.contains(asm.appId),
      isDefaultGroupStore = asm.defaultGroupStore.contains(asm.appId)
    )
  }

  implicit def tokenModelToApi(token: TokenRepository): GestaltToken = OpaqueToken(token.id.asInstanceOf[UUID], ACCESS_TOKEN)

  implicit def apikeyModelToApi(apiKey: APICredentialRepository): GestaltAPIKey = GestaltAPIKey(
    apiKey = apiKey.apiKey.asInstanceOf[UUID].toString,
    apiSecret = Some(apiKey.apiSecret),
    accountId = apiKey.accountId.asInstanceOf[UUID],
    disabled = apiKey.disabled
  )

  implicit def groupMembershipModelToPlugin(gmr: GroupMembershipRepository): GroupMembership = GroupMembership(
    accountId = gmr.accountId.asInstanceOf[UUID],
    groupId = gmr.groupId.asInstanceOf[UUID]
  )
}
