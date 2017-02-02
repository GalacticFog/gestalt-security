package com.galacticfog.gestalt.security.data.domain

import java.util.UUID

import com.galacticfog.gestalt.security.api.errors.{ConflictException, ResourceNotFoundException, UnknownAPIException, BadRequestException}
import com.galacticfog.gestalt.security.api._
import com.galacticfog.gestalt.security.data.model._
import org.mindrot.jbcrypt.BCrypt
import org.postgresql.util.PSQLException
import play.api.Logger
import scalikejdbc._
import scalikejdbc.TxBoundary.Try._

import scala.util.{Try, Success, Failure}

object AppFactory extends SQLSyntaxSupport[UserAccountRepository] {


  override val autoSession = AutoSession

  def delete(app: GestaltAppRepository)(implicit session: DBSession = autoSession) = {
    GestaltAppRepository.destroy(app)
  }

  def find(appId: UUID)(implicit session: DBSession = autoSession) = {
    GestaltAppRepository.find(appId)
  }

  def create(orgId: UUID, name: String, isServiceOrg: Boolean)(implicit session: DBSession = autoSession): Try[GestaltAppRepository] = {
    Try(GestaltAppRepository.create(
      id = UUID.randomUUID(),
      name = name,
      orgId = orgId,
      serviceOrgId = if (isServiceOrg) Some(orgId) else None
    ))
  }

  def listAccountStoreMappings(appId: UUID)(implicit session: DBSession = autoSession): Seq[AccountStoreMappingRepository] = {
    AccountStoreMappingRepository.findAllBy(sqls"app_id = ${appId}")
  }

  def mapGroupToApp(appId: UUID, groupId: UUID, defaultAccountStore: Boolean)(implicit session: DBSession = autoSession): Try[AccountStoreMappingRepository] = {
    Try(AccountStoreMappingRepository.create(
      id = UUID.randomUUID,
      appId = appId,
      storeType = GROUP.label,
      accountStoreId = groupId,
      defaultAccountStore = if (defaultAccountStore) Some(appId) else None,
      defaultGroupStore = None
    ))
  }

  def mapDirToApp(appId: UUID, dirId: UUID, defaultAccountStore: Boolean, defaultGroupStore: Boolean)(implicit session: DBSession = autoSession): Try[AccountStoreMappingRepository] = {
    Try(AccountStoreMappingRepository.create(
      id = UUID.randomUUID,
      appId = appId,
      storeType = DIRECTORY.label,
      accountStoreId = dirId,
      defaultAccountStore = if (defaultAccountStore) Some(appId) else None,
      defaultGroupStore = if (defaultGroupStore) Some(appId) else None
    ))
  }

  def getDefaultGroupStore(appId: UUID)(implicit session: DBSession = autoSession): Try[GestaltDirectoryRepository] = {
    AccountStoreMappingRepository.findBy(sqls"default_group_store = ${appId}") match {
      case None => Failure(BadRequestException(
        resource = "",
        message = "specified app does not have a default group store",
        developerMessage = "Specified application does not have a default group store. Please provide one or create the group directly in the desired directory."
      ))
      case Some(asm) => asm.storeType match {
        case "GROUP" =>
          Failure(BadRequestException(
            resource = "",
            message = "application is configured with an invalid default group store",
            developerMessage = "App is configured with an invalid default group store. The store corresponds to a group, which cannot be used to store groups. The default group store for the application should be removed or changed to correspond to a directory."
          ))
        case "DIRECTORY" =>
          GestaltDirectoryRepository.find(asm.accountStoreId) match {
            case None => Failure(UnknownAPIException(
              code = 500,
              resource = "",
              message = "could not locate directory default group store",
              developerMessage = "Could not load the directory assigned as the default group store for the requested org. This likely indicates an error in the database."
            ))
            case Some(dir) => Success(dir)
          }
      }
    }
  }

  def getDefaultAccountStore(appId: UUID)(implicit session: DBSession = autoSession): Try[Either[GestaltDirectoryRepository,UserGroupRepository]] = {
    AccountStoreMappingRepository.findBy(sqls"default_account_store = ${appId}") match {
      case Some(asm) =>
        asm.storeType match {
          case "GROUP" =>
            UserGroupRepository.find(asm.accountStoreId) match {
              case Some(group) => Success(Right(group))
              case None =>
                throw UnknownAPIException(500, resource = s"/groups/${asm.accountStoreId}", message = "error accessing group corresponding to application's default account store", developerMessage = "")
            }
          case "DIRECTORY" =>
            GestaltDirectoryRepository.find(asm.accountStoreId) match {
              case Some(dir) => Success(Left(dir))
              case None =>
                throw UnknownAPIException(500, resource = s"/directories/${asm.accountStoreId}", message = "error accessing directory corresponding to application's default account store", developerMessage = "")
            }
        }
      case None =>
        AppFactory.find(appId) match {
          case Some(_) => Failure(BadRequestException(
            resource = s"/apps/${appId}",
            message = "application does not have a default account store",
            developerMessage = "Some operation attempted to use the application's default account store, but it does not have one. You will need to add one or avoid relying on its existence."
          ))
          case None => Failure(BadRequestException(
            resource = s"s/apps/${appId}",
            message = "cannot get default account store because application does not exist",
            developerMessage = "App does not exist and therefore does not have a default account store."
          ))
        }
    }
  }

  def createOrgAccountStoreMapping(orgId: UUID, create: GestaltAccountStoreMappingCreate)(implicit session: DBSession = autoSession): Try[AccountStoreMappingRepository] = {
    val mapping = for {
      _ <- OrgFactory.find(orgId) match {
        case Some(_) => Success(Unit)
        case None => Failure(ResourceNotFoundException(
          resource = s"/orgs/${orgId}/accountStores",
          message = "org not found",
          developerMessage = "Organization with the specified ID does not exist."
        ))
      }
      _ <- if (create.isDefaultGroupStore && create.storeType != DIRECTORY) Failure(BadRequestException(
        resource = s"/orgs/${orgId}/accountStores",
        message = "default group store must be an account store of type DIRECTORY",
        developerMessage = "A default group store must correspond to an account store of type DIRECTORY."
      )) else Success(Unit)
      _ <- create.storeType match {
        case DIRECTORY if GestaltDirectoryRepository.find(create.accountStoreId).isEmpty => Failure(BadRequestException(
          resource = s"/orgs/${orgId}/accountStores",
          message = "account store does not correspond to an existing directory",
          developerMessage = "The account store indicates type DIRECTORY, but there is no directory with the given account store ID."
        ))
        case GROUP if UserGroupRepository.find(create.accountStoreId).isEmpty => Failure(BadRequestException(
          resource = s"/orgs/${orgId}/accountStores",
          message = "account store does not correspond to an existing group",
          developerMessage = "The account store indicates type GROUP, but there is no group with the given account store ID."
        ))
        case _ => Success(Unit)
      }
      app <- findServiceAppForOrg(orgId) match {
        case Some(app) => Success(app)
        case None => Failure(BadRequestException(
          resource = s"/orgs/${orgId}/accountStores",
          message = "could not locate service application for the specified org",
          developerMessage = "Could not locate the service application for the specified organization."
        ))
      }
      appId = app.id.asInstanceOf[UUID]
      asm <- Try(AccountStoreMappingRepository.create(
        id = UUID.randomUUID(),
        appId = appId,
        storeType = create.storeType.label,
        accountStoreId = create.accountStoreId,
        name = Some(create.name),
        description = create.description,
        defaultAccountStore = if (create.isDefaultAccountStore) Some(appId) else None,
        defaultGroupStore = if (create.isDefaultGroupStore) Some(appId) else None
      ))
    } yield asm
    mapping recoverWith {
      case t: PSQLException if t.getSQLState == "23505" || t.getSQLState == "23503" =>
        t.getServerErrorMessage.getConstraint match {
          case "account_store_mapping_app_id_store_type_account_store_id_key" =>
            Failure(ConflictException(
              resource = s"/orgs/${orgId}/accountStores",
              message = "mapping already exists",
              developerMessage = "There already exists a mapping on this org to the specified group or directory."
            ))
          case "account_store_mapping_default_group_store_key" =>
            Failure(ConflictException(
              resource = s"/orgs/${orgId}/accountStores",
              message = "default group store already set",
              developerMessage = "There already exists a default group store on this org. The existing default must be removed before a new one can be set."
            ))
          case "account_store_mapping_default_account_store_key" =>
            Failure(ConflictException(
              resource = s"/orgs/${orgId}/accountStores",
              message = "default account store already set",
              developerMessage = "There already exists a default account store on this org. The existing default must be removed before a new one can be set."
            ))
          case _ =>
            Logger.error("PSQLException in saveAccount", t)
            Failure(UnknownAPIException(
              code = 500,
              resource = "",
              message = "sql error",
              developerMessage = "SQL error updating account. Check the error log for more details."
            ))
        }
    }
  }

  def createAppAccountStoreMapping(appId: UUID, create: GestaltAccountStoreMappingCreate)(implicit session: DBSession = autoSession): Try[AccountStoreMappingRepository] = {
    val mapping = for {
      _ <- if (create.isDefaultGroupStore && create.storeType != DIRECTORY) {
        Failure(BadRequestException(
          resource = s"/apps/${appId}/accountStores",
          message = "default group store must be an account store of type DIRECTORY",
          developerMessage = "A default group store must correspond to an account store of type DIRECTORY."
        ))
      } else Success(Unit)
      _ <- create.storeType match {
        case DIRECTORY if GestaltDirectoryRepository.find(create.accountStoreId).isEmpty => Failure(BadRequestException(
          resource = s"/apps/${appId}/accountStores",
          message = "account store does not correspond to an existing directory",
          developerMessage = "The account store indicates type DIRECTORY, but there is no directory with the given account store ID."
        ))
        case GROUP if UserGroupRepository.find(create.accountStoreId).isEmpty => Failure(BadRequestException(
          resource = s"/apps/${appId}/accountStores",
          message = "account store does not correspond to an existing group",
          developerMessage = "The account store indicates type GROUP, but there is no group with the given account store ID."
        ))
        case _ => Success(Unit)
      }
      asm <- Try(AccountStoreMappingRepository.create(
        id = UUID.randomUUID(),
        appId = appId,
        storeType = create.storeType.label,
        accountStoreId = create.accountStoreId,
        name = Some(create.name),
        description = create.description,
        defaultAccountStore = if (create.isDefaultAccountStore) Some(appId) else None,
        defaultGroupStore = if (create.isDefaultGroupStore) Some(appId) else None
      ))
    } yield asm
    mapping recoverWith {
      case t: PSQLException if t.getSQLState == "23505" || t.getSQLState == "23503" =>
        t.getServerErrorMessage.getConstraint match {
          case "account_store_mapping_app_id_store_type_account_store_id_key" =>
            Failure(ConflictException(
              resource = s"/apps/${appId}/accountStores",
              message = "mapping already exists",
              developerMessage = "There already exists a mapping on this application to the specified group or directory."
            ))
          case "account_store_mapping_default_account_store_key" =>
            Failure(ConflictException(
              resource = s"/apps/${appId}/accountStores",
              message = "default account store already set",
              developerMessage = "There already exists a default account store on this application. The existing default must be removed before a new one can be set."
            ))
          case "account_store_mapping_default_group_store_key" =>
            Failure(ConflictException(
              resource = s"/apps/${appId}/accountStores",
              message = "default group store already set",
              developerMessage = "There already exists a default group store on this application. The existing default must be removed before a new one can be set."
            ))
          case _ =>
            Logger.error("PSQLException in saveAccount", t)
            Failure(UnknownAPIException(
              code = 500,
              resource = "",
              message = "sql error",
              developerMessage = "SQL error updating account. Check the error log for more details."
            ))
        }
    }
  }

  def createAccountInApp(appId: UUID, create: GestaltAccountCreateWithRights)(implicit session: DBSession = autoSession): Try[UserAccountRepository] = {
    DB localTx { implicit session =>
      // have to find the default account store for the app
      // if it's a directory, add the account to the directory
      // if it's a group, add the account to the group's directory and then to the group
      // then add the account to any groups specified in the create request
      val cred = create.credential.asInstanceOf[GestaltPasswordCredential]
      val newUserTry = for {
        asm <- getDefaultAccountStore(appId)
        dirId = asm.fold(_.id, _.dirId).asInstanceOf[UUID]
        newUser <- AccountFactory.createAccount(
          dirId = dirId,
          username = create.username,
          description = create.description,
          email = create.email.map(_.trim).filter(!_.isEmpty),
          phoneNumber = create.phoneNumber.map(pn => AccountFactory.canonicalE164(pn.trim)),
          firstName = create.firstName,
          lastName = create.lastName,
          hashMethod = "bcrypt",
          secret = BCrypt.hashpw(cred.password, BCrypt.gensalt()),
          salt = "",
          disabled = false
        )
        newUserId = newUser.id.asInstanceOf[UUID]
        // add grants
        rights <- Try{ create.rights.toSeq.flatten map {
          cr => RightGrantFactory.addRightToAccount(
            appId = appId,
            accountId = newUserId,
            right = cr
          ).get
        } }
        // add groups
        groups <- Try {
          (create.groups.toSeq.flatten ++ asm.right.toSeq.map {
            _.id.asInstanceOf[UUID]
          }).distinct.map {
            groupId => GroupFactory.addAccountToGroup(accountId = newUserId, groupId = groupId).get
          }
        }
      } yield newUser
      newUserTry
    }
  }

  def createGroupInApp(appId: UUID, create: GestaltGroupCreateWithRights)(implicit session: DBSession = autoSession): Try[UserGroupRepository] = {
    DB localTx { implicit session =>
      // have to find the default group store for the app
      // then add the group to the directory
      // then associate any rights specified in the request
      if (GestaltAppRepository.find(appId).isEmpty) {
        throw ResourceNotFoundException(
          resource = s"/apps/${appId}",
          message = "could not create group in non-existent application",
          developerMessage = "Could not create group in non-existent application. If this was created as a result of an attempt to create a group in an org, it suggests that the org is misconfigured."
        )
      }
      for {
        dir <- getDefaultGroupStore(appId)
        dirId = dir.id.asInstanceOf[UUID]
        newGroup <- GroupFactory.create(
          name = create.name,
          description = create.description,
          dirId = dirId,
          maybeParentOrg = None
        )
        newGroupId = newGroup.id.asInstanceOf[UUID]
        // add grants
        _ <- Try{ create.rights.toSeq.flatten map { grant =>
            RightGrantFactory.addRightToGroup(
              appId = appId,
              groupId = newGroupId,
              right = grant
            ).get
          }
        }
      } yield newGroup
    }
  }

  def findServiceAppForOrg(orgId: UUID)(implicit session: DBSession = autoSession): Option[GestaltAppRepository] = {
    val (a,o) = (
      GestaltAppRepository.syntax("a"),
      GestaltOrgRepository.syntax("o")
      )
    sql"""
    select
      ${a.result.*}
    from
      ${GestaltAppRepository.as(a)} inner join ${GestaltOrgRepository.as(o)} on ${o.id} = ${orgId} and ${a.serviceOrgId} = ${o.id}
    """.map(GestaltAppRepository(a)).single.apply()
  }

  def findServiceAppForFQON(fqon: String)(implicit session: DBSession = autoSession): Option[GestaltAppRepository] = {
    val (a,o) = (
        GestaltAppRepository.syntax("a"),
        GestaltOrgRepository.syntax("o")
      )
    sql"""
    select
      ${a.result.*}
    from
      ${GestaltAppRepository.as(a)} inner join ${GestaltOrgRepository.as(o)} on ${o.fqon} = ${fqon} and ${a.serviceOrgId} = ${o.id}
    """.map(GestaltAppRepository(a)).single.apply()
  }

  def findByAppId(appId: UUID)(implicit session: DBSession = autoSession): Option[GestaltAppRepository] = {
    GestaltAppRepository.find(appId)
  }

  def listByOrgId(orgId: UUID)(implicit session: DBSession = autoSession): List[GestaltAppRepository] = {
    GestaltAppRepository.findAllBy(sqls"org_id=${orgId}")
  }

  def getUsernameInDefaultAccountStore(appId: UUID, username: String)(implicit session: DBSession = autoSession): Try[UserAccountRepository] = {
    for {
      asm <- getDefaultAccountStore(appId)
      dirId = asm.fold(_.id,_.dirId).asInstanceOf[UUID]
      account <- AccountFactory.findInDirectoryByName(dirId, username) match {
        case Some(a) => Success(a)
        case None => Failure(ResourceNotFoundException(
          resource = "",
          message = "could not locate requested account in the application",
          developerMessage = "Could not locate the requested account in the default " +
            "account store associated with the application."
        ))
      }
    } yield account
  }

  def getUsernameInOrgDefaultAccountStore(orgId: UUID, username: String)(implicit session: DBSession = autoSession): Try[UserAccountRepository] = {
    for {
      app <- findServiceAppForOrg(orgId) match {
        case Some(app) => Success(app)
        case None => Failure(ResourceNotFoundException(
          resource = "",
          message = "could not locate service application for the specified org",
          developerMessage = "Could not locate the service application for the specified organization."
        ))
      }
      asm <- getDefaultAccountStore(app.id.asInstanceOf[UUID])
      dirId = asm.fold(_.id,_.dirId).asInstanceOf[UUID]
      account <- AccountFactory.findInDirectoryByName(dirId, username) match {
        case Some(a) => Success(a)
        case None => Failure(ResourceNotFoundException(
          resource = "",
          message = "could not locate requested account in the organization",
          developerMessage = "Could not locate the requested account in the default " +
            "account store associated with the organization."
        ))
      }
    } yield account
  }

  def getGroupNameInOrgDefaultGroupStore(orgId: UUID, groupName: String)(implicit session: DBSession = autoSession): Try[UserGroupRepository] = {
    for {
      app <- findServiceAppForOrg(orgId) match {
        case Some(app) => Success(app)
        case None => Failure(ResourceNotFoundException(
          resource = "",
          message = "could not locate service application for the specified org",
          developerMessage = "Could not locate the service application for the specified organization."
        ))
      }
      asm <- getDefaultGroupStore(app.id.asInstanceOf[UUID])
      dirId = asm.id.asInstanceOf[UUID]
      group <- GroupFactory.findInDirectoryByName(dirId, groupName) match {
        case Some(grp) => Success(grp)
        case None => Failure(ResourceNotFoundException(
          resource = "",
          message = "could not locate requested group in the organization",
          developerMessage = "Could not locate the requested group in the default " +
            "group store associated with the organization."
        ))
      }
    } yield group
  }

  def getGroupNameInAppDefaultGroupStore(appId: UUID, groupName: String)(implicit session: DBSession = autoSession): Try[UserGroupRepository] = {
    for {
      asm <- getDefaultGroupStore(appId)
      dirId = asm.id.asInstanceOf[UUID]
      group <- GroupFactory.findInDirectoryByName(dirId, groupName) match {
        case Some(grp) => Success(grp)
        case None => Failure(ResourceNotFoundException(
          resource = "",
          message = "could not locate requested group in the application",
          developerMessage = "Could not locate the requested group in the default " +
            "group store associated with the application."
        ))
      }
    } yield group
  }

}
