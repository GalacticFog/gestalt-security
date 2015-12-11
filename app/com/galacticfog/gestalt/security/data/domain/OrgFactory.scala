package com.galacticfog.gestalt.security.data.domain

import java.util.UUID
import com.galacticfog.gestalt.security.api._
import com.galacticfog.gestalt.security.api.errors.{UnknownAPIException, CreateConflictException, BadRequestException}
import com.galacticfog.gestalt.security.data.model.{GroupMembershipRepository, GestaltOrgRepository}
import controllers.GestaltHeaderAuthentication.AccountWithOrgContext
import play.api.Logger
import play.api.libs.json.{Json, JsResultException, JsValue}
import play.api.mvc.Security
import scalikejdbc._

object OrgFactory extends SQLSyntaxSupport[GestaltOrgRepository] {

  object Rights {
    val SUPERUSER = "**"

    val CREATE_ORG = "createOrg"
    val DELETE_ORG = "deleteOrg"
    val CREATE_ACCOUNT = "createAccount"
    val UPDATE_ACCOUNT = "updateAccount"
    val DELETE_ACCOUNT = "deleteAccount"
    val CREATE_GROUP = "createGroup"
    val DELETE_GROUP = "deleteGroup"
    val CREATE_DIRECTORY = "createDirectory"
    val DELETE_DIRECTORY = "deleteDirectory"
    val READ_DIRECTORY = "readDirectory"
    val CREATE_APP = "createApp"
    val DELETE_APP = "deleteApp"
    val LIST_ORG_GRANTS = "listOrgGrants"
    val CREATE_ORG_GRANT = "createOrgGrant"
    val DELETE_ORG_GRANT = "deleteOrgGrant"
    val UPDATE_ORG_GRANT = "updateOrgGrant"
    val LIST_APP_GRANTS = "listAppGrants"
    val CREATE_APP_GRANT = "createAppGrant"
    val DELETE_APP_GRANT = "deleteAppGrant"
    val UPDATE_APP_GRANT = "updateAppGrant"
    val CREATE_ACCOUNT_STORE = "createAccountStore"
    val UPDATE_ACCOUNT_STORE = "updateAccountStore"
    val DELETE_ACCOUNT_STORE = "deleteAccountStore"
    val AUTHENTICATE_ACCOUNTS = "authenticateAccounts"

    val NEW_ORG_OWNER_RIGHTS = Seq(
      SUPERUSER
    )
  }

  override val autoSession = AutoSession

  def delete(org: GestaltOrgRepository)(implicit session: DBSession = autoSession) = {
    GestaltOrgRepository.destroy(org)
  }

  def find(orgId: UUID)(implicit session: DBSession = autoSession): Option[GestaltOrgRepository] = {
    GestaltOrgRepository.find(orgId)
  }

  def createSubOrgWithAdmin(parentOrg: GestaltOrgRepository, request: Security.AuthenticatedRequest[JsValue,AccountWithOrgContext])(implicit session: DBSession = autoSession): GestaltOrgRepository = {
    import com.galacticfog.gestalt.security.api.json.JsonImports._
    DB localTx { implicit session =>
      val account = request.user.identity
      val accountId = account.id.asInstanceOf[UUID]
      val create = try {
        request.body.as[GestaltOrgCreate]
      } catch {
        case parseError: JsResultException => throw new BadRequestException(
          resource = request.path,
          message = "invalid payload",
          developerMessage = "Payload could not be parsed; was expecting JSON representation of GestaltOrgCreate"
        )
        case e: Throwable => throw new UnknownAPIException(
          code = 500, resource = request.path, message = "unknown error parsing payload", developerMessage = e.getMessage
        )
      }
      if (create.name.toLowerCase != create.name) throw new BadRequestException(
        resource = request.path,
        message = "org names must be lower case",
        developerMessage = "Org names are required to be lower case."
      )
      // create org
      val newOrg = try {
        OrgFactory.createOrg(parentOrg = parentOrg, name = create.name)
      } catch {
        case _: Throwable => throw new CreateConflictException(
          resource = request.path,
          message = "error creating new sub org",
          developerMessage = "Error creating new sub org. This is most likely a conflict with an existing org of the same name."
        )
      }
      val newOrgId = newOrg.id.asInstanceOf[UUID]
      // create system app
      val newApp = AppFactory.create(orgId = newOrgId, name = newOrgId + "-system-app", isServiceOrg = true)
      val newAppId = newApp.id.asInstanceOf[UUID]
      // create admins group, add user
      val adminGroup = GroupFactory.create(name = newOrgId + "-admins", dirId = account.dirId.asInstanceOf[UUID], parentOrg = newOrgId)
      val adminGroupId = adminGroup.id.asInstanceOf[UUID]
      GroupFactory.addAccountToGroup(accountId = accountId, groupId = adminGroupId)
      AppFactory.mapGroupToApp(appId = newAppId, groupId = adminGroupId, defaultAccountStore = false)
      // give admin rights to new account
      RightGrantFactory.addRightsToGroup(appId = newAppId, groupId = adminGroupId, rights = OrgFactory.Rights.NEW_ORG_OWNER_RIGHTS map {g => GestaltGrantCreate(grantName = g, grantValue = None)})
      // create users group in a new directory under this org, map new group
      if (create.createDefaultUserGroup.contains(true)) {
        val newDir = DirectoryFactory.createDirectory(orgId = newOrgId, GestaltDirectoryCreate(
          name = newOrgId + "-user-dir",
          description = Some(s"automatically created directory for ${newOrg.fqon} to house organization users"),
          config = Some(Json.obj(
            "directoryType" -> "INTERNAL"
          )
        )))
        val usersGroup = GroupFactory.create(name = newOrg.fqon + "-users", dirId = newDir.id, parentOrg = newOrgId)
        val usersGroupId = usersGroup.id.asInstanceOf[UUID]
        AppFactory.mapGroupToApp(appId = newAppId, groupId = usersGroupId, defaultAccountStore = true)
      }
      newOrg
    }
  }

  def getRootOrg()(implicit session: DBSession = autoSession): Option[GestaltOrgRepository] = {
    GestaltOrgRepository.findBy(sqls"parent is null")
  }

  def createOrg(parentOrg: GestaltOrgRepository, name: String)(implicit session: DBSession = autoSession): GestaltOrgRepository = {
      if (name.contains('.')) throw new BadRequestException(resource = s"/orgs/${parentOrg.id}", message = "org names cannot contain periods", developerMessage = "Org names cannot contain periods.")
      val newfqon = if (parentOrg.parent.isDefined) parentOrg.fqon + "." + name else name
      GestaltOrgRepository.create(
        id = UUID.randomUUID(),
        name = name,
        fqon = newfqon,
        parent = Some(parentOrg.id)
      )
    }

  def findByFQON(fqon: String)(implicit session: DBSession = autoSession): Option[GestaltOrgRepository] = {
    Logger.info("looking for org with fqon = " + fqon)
    GestaltOrgRepository.findBy(sqls"fqon = ${fqon}")
  }

  def findByOrgId(orgId: UUID)(implicit session: DBSession = autoSession): Option[GestaltOrgRepository] = {
    Logger.info("looking for org with org_id = " + orgId)
    GestaltOrgRepository.find(orgId)
  }

  def getChildren(parentOrgId: UUID)(implicit session: DBSession = autoSession): Seq[GestaltOrgRepository] = GestaltOrgRepository.findAllBy(sqls"parent = ${parentOrgId}")

  def getOrgTree(rootId: UUID)(implicit session: DBSession = autoSession): Seq[GestaltOrgRepository] = {
    sql"""WITH RECURSIVE org_tree (id, name, fqon, level, parent)
      AS (
      SELECT
      id,
      name,
      fqon,
      0,
      parent
      FROM org
      WHERE id = ${rootId}

      UNION ALL
      SELECT
      org.id,
      org.name,
      org.fqon,
      org_tree.level + 1,
      org_tree.id
      FROM org, org_tree
      WHERE org.parent = org_tree.id
      )
      SELECT org_tree.id,org_tree.name,org_tree.fqon,org_tree.parent FROM org_tree
      ORDER BY level, fqon""".map(rs => GestaltOrgRepository(
        id = rs.any("id"),
        name = rs.string("name"),
        fqon = rs.string("fqon"),
        parent = rs.anyOpt("parent")
      )).list().apply()
  }

}
