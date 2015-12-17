package com.galacticfog.gestalt.security.data.domain

import java.util.UUID
import com.galacticfog.gestalt.security.api._
import com.galacticfog.gestalt.security.api.errors.{ResourceNotFoundException, UnknownAPIException, CreateConflictException, BadRequestException}
import com.galacticfog.gestalt.security.data.model.{UserAccountRepository, GestaltOrgRepository}
import controllers.GestaltHeaderAuthentication.AccountWithOrgContext
import org.postgresql.util.PSQLException
import play.api.Logger
import play.api.libs.json.{Json, JsResultException, JsValue}
import play.api.mvc.Security
import scalikejdbc._

object OrgFactory extends SQLSyntaxSupport[GestaltOrgRepository] {

  val VALID_NAME = """^[a-z0-9]+(-[a-z0-9]+)*[a-z0-9]*$""".r

  object Rights {
    val SUPERUSER = "**"

    val CREATE_ORG = "createOrg"
    val UPDATE_ORG = "updateOrg"
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

// ugh, what a hassle. will do this later.
//  def updateOrg(orgId: UUID, newName: String)(implicit session: DBSession = autoSession): GestaltOrgRepository = {
//    OrgFactory.find(orgId) match {
//      case Some(org) =>
//        val parent = org.parent flatMap {u => OrgFactory.find(u.asInstanceOf[UUID])}
//        val updated = org.copy(
//          name = newName
//        )
//      case None => throw new ResourceNotFoundException(
//        resource = "",
//        message = "org does not exist",
//        developerMessage = "An org with the given ID does not exist."
//      )
//    }
//  }

  def find(orgId: UUID)(implicit session: DBSession = autoSession): Option[GestaltOrgRepository] = {
    GestaltOrgRepository.find(orgId)
  }

  def createSubOrgWithAdmin(parentOrgId: UUID, creator: UserAccountRepository, create: GestaltOrgCreate)(implicit session: DBSession = autoSession): GestaltOrgRepository = {
    DB localTx { implicit session =>
      if (VALID_NAME.findFirstIn(create.name).isEmpty) throw new BadRequestException(
        resource = "",
        message = "org name is invalid",
        developerMessage = "Org names are required to be lower case letters, digits, and non-consecutive/trailing/preceding hyphens."
      )
      // create org
      val newOrg = createOrg(parentOrgId = parentOrgId, name = create.name)
      val newOrgId = newOrg.id.asInstanceOf[UUID]
      // create system app
      val newApp = AppFactory.create(orgId = newOrgId, name = newOrgId + "-system-app", isServiceOrg = true)
      val newAppId = newApp.id.asInstanceOf[UUID]
      // create admins group, add user
      val adminGroup = GroupFactory.create(name = newOrgId + "-admins", dirId = creator.dirId.asInstanceOf[UUID], parentOrg = newOrgId)
      val adminGroupId = adminGroup.id.asInstanceOf[UUID]
      GroupFactory.addAccountToGroup(accountId = creator.id.asInstanceOf[UUID], groupId = adminGroupId)
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

  def createOrg(parentOrgId: UUID, name: String)(implicit session: DBSession = autoSession): GestaltOrgRepository = {
    val parentOrg = OrgFactory.find(parentOrgId) getOrElse {
      throw new ResourceNotFoundException(resource = "", message = "could not locate parent org", "Could not find the parent org while attempting to create a sub-org.")
    }
    val newfqon = if (parentOrg.parent.isDefined) parentOrg.fqon + "." + name else name
    try {
      GestaltOrgRepository.create(
        id = UUID.randomUUID(),
        name = name,
        fqon = newfqon,
        parent = Some(parentOrg.id)
      )
    } catch {
      case t: PSQLException if (t.getSQLState == "23505") && (t.getServerErrorMessage.getConstraint == "org_parent_name_key") =>
        val v = t.getServerErrorMessage
        throw new CreateConflictException(
          resource = "",
          message = "org name already exists in the parent org",
          developerMessage = "The parent org already has a sub-org with the requested name. Pick a new name or a different parent."
        )
      case t: Throwable => throw t
    }
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
