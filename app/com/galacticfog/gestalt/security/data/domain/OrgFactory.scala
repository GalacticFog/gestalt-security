package com.galacticfog.gestalt.security.data.domain

import java.util.UUID
import com.galacticfog.gestalt.security.api._
import com.galacticfog.gestalt.security.api.errors.{ResourceNotFoundException, UnknownAPIException, ConflictException, BadRequestException}
import com.galacticfog.gestalt.security.data.model.{GestaltAppRepository, UserAccountRepository, GestaltOrgRepository}
import controllers.GestaltHeaderAuthentication.AccountWithOrgContext
import org.postgresql.util.PSQLException
import play.api.Logger
import play.api.libs.json.{Json, JsResultException, JsValue}
import play.api.mvc.Security
import scalikejdbc._
import scalikejdbc.TxBoundary.Try._

import scala.util.{Success, Try, Failure}

object OrgFactory extends SQLSyntaxSupport[GestaltOrgRepository] {

  val VALID_NAME = """^[a-z0-9]+(-[a-z0-9]+)*[a-z0-9]*$""".r

  def validateOrgName(name: String): Try[String] = {
    VALID_NAME.findFirstIn(name) match {
      case None => Failure(BadRequestException(
        resource = "",
        message = "org name is invalid",
        developerMessage = "Org names are required to be lower case letters, digits, and non-consecutive/trailing/preceding hyphens."
      ))
      case Some(validName) => Success(validName)
    }
  }

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
    val UPDATE_GROUP = "updateGroup"
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
    val DELETE_TOKEN = "deleteToken"
    val CREATE_APIKEY = "createAPIKey"
    val DELETE_APIKEY = "deleteAPIKey"

    val NEW_ORG_OWNER_RIGHTS = Seq(
      SUPERUSER
    )
  }

  override val autoSession = AutoSession

  def delete(org: GestaltOrgRepository)(implicit session: DBSession = autoSession) = {
    GestaltOrgRepository.destroy(org)
  }

  def orgSync(orgId: UUID)(implicit session: DBSession = autoSession): GestaltOrgSync = {
    import com.galacticfog.gestalt.security.data.APIConversions.dirModelToApi
    import com.galacticfog.gestalt.security.data.APIConversions.orgModelToApi
    import com.galacticfog.gestalt.security.data.APIConversions.groupModelToApi
    val orgTree = OrgFactory.getOrgTree(orgId) flatMap {
      org => AppFactory.findServiceAppForOrg(org.id.asInstanceOf[UUID]) map { (org,_) }
    }
    val dirCache = DirectoryFactory.findAll map {
      dir => (dir.id, dir)
    } toMap
    val orgUsers = (orgTree flatMap {
      case (org,sApp) => AccountFactory.listAppUsers(sApp.id.asInstanceOf[UUID])
    } distinct) flatMap {
      uar => dirCache.get(uar.dirId.asInstanceOf[UUID]) map { dir =>
        GestaltAccount(
          id = uar.id.asInstanceOf[UUID],
          username = uar.username,
          description = uar.description,
          firstName = uar.firstName,
          lastName = uar.lastName,
          email = uar.email,
          phoneNumber = uar.phoneNumber,
          directory = dir
        )
      }
    }
    val orgGroups = (orgTree flatMap {
      case (org,sApp) => GroupFactory.queryShadowedAppGroups(sApp.id.asInstanceOf[UUID], None)
    } distinct) map { ugr => ugr: GestaltGroup }
    val memberships = orgGroups.map {g =>
      (g.id -> GroupFactory.listGroupAccounts(g.id).map{_.id.asInstanceOf[UUID]})
    }.toMap
    GestaltOrgSync(
      accounts = orgUsers,
      groups = orgGroups,
      orgs = orgTree map { case (o,_) => o: GestaltOrg }
    )
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

  def createSubOrgWithAdmin(parentOrgId: UUID, creator: UserAccountRepository, create: GestaltOrgCreate)(implicit session: DBSession = autoSession): Try[GestaltOrgRepository] = {
    DB localTx { implicit session =>
      // create org
      if (create.createDefaultUserGroup && create.inheritParentMappings.contains(true)) return Failure(BadRequestException(
        resource = "",
        message = "cannot inherit permissions and create default account group",
        developerMessage = "Please choose one of either account store mapping inheritance or default user group creation."
      ))
      val newOrgAndAppIdTry = for {
        newOrg <- createOrg(parentOrgId = parentOrgId, name = create.name, description = create.description)
        newOrgId = newOrg.id.asInstanceOf[UUID]
        // create system app
        newApp <- AppFactory.create(orgId = newOrgId, name = newOrgId + "-system-app", isServiceOrg = true)
        newAppId = newApp.id.asInstanceOf[UUID]
        // create admins group, add user
        adminGroup <- GroupFactory.create(
          name = newOrgId + "-admins",
          dirId = creator.dirId.asInstanceOf[UUID],
          maybeParentOrg = Some(newOrgId),
          description = Some(s"automatically created admin group for ${newOrg.fqon} to house creator")
        )
        adminGroupId = adminGroup.id.asInstanceOf[UUID]
        _ <- GroupFactory.addAccountToGroup(accountId = creator.id.asInstanceOf[UUID], groupId = adminGroupId)
        _ <- AppFactory.mapGroupToApp(appId = newAppId, groupId = adminGroupId, defaultAccountStore = false)
        // give admin rights to new admin group
        _ <- Try { OrgFactory.Rights.NEW_ORG_OWNER_RIGHTS map {
          g => RightGrantFactory.addRightToGroup(
            appId = newAppId,
            groupId = adminGroupId,
            right = GestaltGrantCreate(grantName = g, grantValue = None)).get
        } }
      } yield (newOrg,newAppId)
      // create users group in a new directory under this org, map new group
      if (create.createDefaultUserGroup) {
        for {
          (newOrg,newAppId) <- newOrgAndAppIdTry
          newOrgId = newOrg.id.asInstanceOf[UUID]
          newDir <- DirectoryFactory.createDirectory(
            orgId = newOrgId,
            create = GestaltDirectoryCreate(
              name = newOrgId + "-user-dir",
              description = Some(s"automatically created directory for ${newOrg.fqon} to house organization users and groups"),
              config = None,
              directoryType = DIRECTORY_TYPE_INTERNAL
            )
          )
          usersGroup <- GroupFactory.create(
            name = newOrg.fqon + "-users",
            dirId = newDir.id,
            maybeParentOrg = Some(newOrgId),
            description = Some(s"automatically created user group for ${newOrg.fqon} to house users")
          )
          usersGroupId = usersGroup.id.asInstanceOf[UUID]
          _ <- AppFactory.mapGroupToApp(appId = newAppId, groupId = usersGroupId, defaultAccountStore = true)
          _ <- AppFactory.mapDirToApp(appId = newAppId, dirId = newDir.id, defaultAccountStore = false, defaultGroupStore = true)
        } yield usersGroupId
      }
      if (create.inheritParentMappings.contains(true)) {
        for {
          (newOrg,newAppId) <- newOrgAndAppIdTry
          newOrgId = newOrg.id.asInstanceOf[UUID]
          parentApp <- AppFactory.findServiceAppForOrg(parentOrgId).fold[Try[GestaltAppRepository]](Failure(UnknownAPIException(
            resource = "", code = 500, message = "could not find service app for parent org", developerMessage = "Could not find the service app for the parent org. This is not an expected error."
          )))(Success(_))
          parentAppId = parentApp.id.asInstanceOf[UUID]
          mappings <- Try{ for {
            parentMapping <- AppFactory.listAccountStoreMappings(parentAppId)
            childMapping = parentMapping.storeType match {
              case "DIRECTORY" =>
                AppFactory.mapDirToApp(
                  appId = newAppId,
                  dirId = parentMapping.accountStoreId.asInstanceOf[UUID],
                  defaultAccountStore = parentMapping.defaultAccountStore.contains(parentAppId),
                  defaultGroupStore = parentMapping.defaultGroupStore.contains(parentAppId)
                )
              case "GROUP" =>
                AppFactory.mapGroupToApp(
                  appId = newAppId,
                  groupId = parentMapping.accountStoreId.asInstanceOf[UUID],
                  defaultAccountStore = parentMapping.defaultAccountStore.contains(parentAppId)
                )
            }
          } yield childMapping.get}
        } yield mappings
      }
      newOrgAndAppIdTry map {_._1}
    }
  }

  def getRootOrg()(implicit session: DBSession = autoSession): Option[GestaltOrgRepository] = {
    GestaltOrgRepository.findBy(sqls"parent is null")
  }

  def createOrg(parentOrgId: UUID, name: String, description: Option[String])(implicit session: DBSession = autoSession): Try[GestaltOrgRepository] = {
    val newOrg = for {
      validName <- validateOrgName(name)
      parentOrg <- OrgFactory.find(parentOrgId) match {
        case None => Failure(ResourceNotFoundException(
          resource = "",
          message = "could not locate parent org",
          developerMessage = "Could not find the parent org while attempting to create a sub-org."))
        case Some(org) => Success(org)
      }
      newfqon = if (parentOrg.parent.isDefined) parentOrg.fqon + "." + name else name
      newOrg <- Try(GestaltOrgRepository.create(
        id = UUID.randomUUID(),
        name = name,
        fqon = newfqon,
        parent = Some(parentOrg.id),
        description = description
      ))
    } yield newOrg
    newOrg recoverWith {
      case t: PSQLException if (t.getSQLState == "23505") && (t.getServerErrorMessage.getConstraint == "org_parent_name_key") =>
        Failure(ConflictException(
          resource = "",
          message = "org name already exists in the parent org",
          developerMessage = "The parent org already has a sub-org with the requested name. Pick a new name or a different parent."
        ))
    }
  }

  def findByFQON(fqon: String)(implicit session: DBSession = autoSession): Option[GestaltOrgRepository] = {
    GestaltOrgRepository.findBy(sqls"fqon = ${fqon}")
  }

  def findByOrgId(orgId: UUID)(implicit session: DBSession = autoSession): Option[GestaltOrgRepository] = {
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
