package com.galacticfog.gestalt.security.data.domain

import java.util.UUID

import com.galacticfog.gestalt.security.api.errors.{ConflictException, ResourceNotFoundException, UnknownAPIException, BadRequestException}
import com.galacticfog.gestalt.security.api._
import com.galacticfog.gestalt.security.data.model._
import org.mindrot.jbcrypt.BCrypt
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

  def getDefaultGroupStore(appId: UUID)(implicit session: DBSession = autoSession): GestaltDirectoryRepository = {
    AccountStoreMappingRepository.findBy(sqls"default_group_store = ${appId}") match {
      case None => throw new BadRequestException(
        resource = "",
        message = "specified app does not have a default group store",
        developerMessage = "Specified app does not have a default group store. Please provide one or create the group directly in the desired directory."
      )
      case Some(asm) => asm.storeType match {
        case "GROUP" =>
          throw new BadRequestException(
            resource = "",
            message = "app is configured with an invalid default group store",
            developerMessage = "App is configured with an invalid default group store. The store corresponds to a group, which cannot be used to store groups. The default group store for the app should be removed or changed to correspond to a directory."
          )
        case "DIRECTORY" =>
          GestaltDirectoryRepository.find(asm.accountStoreId) match {
            case None => throw new UnknownAPIException(
              code = 500,
              resource = "",
              message = "could not locate directory default group store",
              developerMessage = "Could not load the directory assigned as the default group store for the requested org. This likely indicates an error in the database."
            )
            case Some(dir) => dir
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
                throw new UnknownAPIException(500, resource = s"/groups/${asm.accountStoreId}", message = "error accessing group corresponding to app's default account store", developerMessage = "")
            }
          case "DIRECTORY" =>
            GestaltDirectoryRepository.find(asm.accountStoreId) match {
              case Some(dir) => Success(Left(dir))
              case None =>
                throw new UnknownAPIException(500, resource = s"/directories/${asm.accountStoreId}", message = "error accessing directory corresponding to app's default account store", developerMessage = "")
            }
        }
      case None =>
        AppFactory.find(appId) match {
          case Some(_) => Failure(BadRequestException(
            resource = s"/apps/${appId}",
            message = "app does not have a default account store",
            developerMessage = "Some operation attempted to use the app's default account store, but it does not have one. You will need to add one or avoid relying on its existence."
          ))
          case None => Failure(BadRequestException(
            resource = s"s/apps/${appId}",
            message = "cannot get default account store because app does not exist",
            developerMessage = "App does not exist and therefore does not have a default account store."
          ))
        }
    }
  }

  def createOrgAccountStoreMapping(appId: UUID, create: GestaltOrgAccountStoreMappingCreate)(implicit session: DBSession = autoSession): AccountStoreMappingRepository = {
    if (AccountStoreMappingRepository.findBy(sqls"app_id = ${appId} and store_type = ${create.storeType.label} and account_store_id = ${create.accountStoreId}").isDefined) {
      throw new ConflictException(
        resource = s"/apps/${appId}/accountStores",
        message = "mapping already exists",
        developerMessage = "There already exists a mapping on this application/org to the specified group or directory."
      )
    }
    if (create.isDefaultAccountStore && AccountStoreMappingRepository.findBy(sqls"default_account_store = ${appId}").isDefined) {
      throw new ConflictException(
        resource = s"/apps/${appId}/accountStores",
        message = "default account store already set",
        developerMessage = "There already exists a default account store on this application/org. This default must be removed before a new one can be set."
      )
    }
    if (create.isDefaultGroupStore && AccountStoreMappingRepository.findBy(sqls"default_group_store = ${appId}").isDefined) {
      throw new ConflictException(
        resource = s"/apps/${appId}/accountStores",
        message = "default group store already set",
        developerMessage = "There already exists a default group store on this application/org. This default must be removed before a new one can be set."
      )
    }
    if (create.isDefaultGroupStore && create.storeType != DIRECTORY) {
      throw new BadRequestException(
        resource = s"/apps/${appId}/accountStores",
        message = "default group store must be an account store of type DIRECTORY",
        developerMessage = "A default group store must correspond to an account store of type DIRECTORY."
      )
    }
    create.storeType match {
      case DIRECTORY => if (GestaltDirectoryRepository.find(create.accountStoreId).isEmpty) {
        throw new BadRequestException(
          resource = s"/apps/${appId}/accountStores",
          message = "account store does not correspond to an existing directory",
          developerMessage = "The account store indicates type DIRECTORY, but there is no directory with the given account store ID."
        )
      }
      case GROUP => if (UserGroupRepository.find(create.accountStoreId).isEmpty) {
        throw new BadRequestException(
          resource = s"/apps/${appId}/accountStores",
          message = "account store does not correspond to an existing group",
          developerMessage = "The account store indicates type GROUP, but there is no group with the given account store ID."
        )
      }
    }
    AccountStoreMappingRepository.create(
      id = UUID.randomUUID(),
      appId = appId,
      storeType = create.storeType.label,
      accountStoreId = create.accountStoreId,
      name = Some(create.name),
      description = Some(create.description),
      defaultAccountStore = if (create.isDefaultAccountStore) Some(appId) else None,
      defaultGroupStore = if (create.isDefaultGroupStore) Some(appId) else None
    )
  }

  def createAccountStoreMapping(appId: UUID, create: GestaltAccountStoreMappingCreate)(implicit session: DBSession = autoSession): AccountStoreMappingRepository = {
    if (AccountStoreMappingRepository.findBy(sqls"app_id = ${appId} and store_type = ${create.storeType.label} and account_store_id = ${create.accountStoreId}").isDefined) {
      throw new ConflictException(
        resource = s"/apps/${appId}/accountStores",
        message = "mapping already exists",
        developerMessage = "There already exists a mapping on this application/org to the specified group or directory."
      )
    }
    if (create.isDefaultAccountStore && AccountStoreMappingRepository.findBy(sqls"default_account_store = ${create.appId}").isDefined) {
      throw new ConflictException(
        resource = s"/apps/${appId}/accountStores",
        message = "default account store already set",
        developerMessage = "There already exists a default account store on this application/org. This default must be removed before a new one can be set."
      )
    }
    if (create.isDefaultGroupStore && AccountStoreMappingRepository.findBy(sqls"default_group_store = ${create.appId}").isDefined) {
      throw new ConflictException(
        resource = s"/apps/${appId}/accountStores",
        message = "default group store already set",
        developerMessage = "There already exists a default group store on this application/org. This default must be removed before a new one can be set."
      )
    }
    if (create.isDefaultGroupStore && create.storeType != DIRECTORY) {
      throw new BadRequestException(
        resource = s"/apps/${appId}/accountStores",
        message = "default group store must be an account store of type DIRECTORY",
        developerMessage = "A default group store must correspond to an account store of type DIRECTORY."
      )
    }
    create.storeType match {
      case DIRECTORY => if (GestaltDirectoryRepository.find(create.accountStoreId).isEmpty) {
        throw new BadRequestException(
          resource = s"/apps/${appId}/accountStores",
          message = "account store does not correspond to an existing directory",
          developerMessage = "The account store indicates type DIRECTORY, but there is no directory with the given account store ID."
        )
      }
      case GROUP => if (UserGroupRepository.find(create.accountStoreId).isEmpty) {
        throw new BadRequestException(
          resource = s"/apps/${appId}/accountStores",
          message = "account store does not correspond to an existing group",
          developerMessage = "The account store indicates type GROUP, but there is no group with the given account store ID."
        )
      }
    }
    AccountStoreMappingRepository.create(
      id = UUID.randomUUID(),
      appId = create.appId,
      storeType = create.storeType.label,
      accountStoreId = create.accountStoreId,
      name = Some(create.name),
      description = Some(create.description),
      defaultAccountStore = if (create.isDefaultAccountStore) Some(appId) else None,
      defaultGroupStore = if (create.isDefaultGroupStore) Some(appId) else None
    )
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
          email = create.email.trim match {
            case "" => None
            case e => Some(e)
          },
          phoneNumber = create.phoneNumber.trim match {
            case "" => None
            case p => Some(AccountFactory.canonicalE164(p))
          },
          firstName = create.firstName,
          lastName = create.lastName,
          hashMethod = "bcrypt",
          secret = BCrypt.hashpw(cred.password, BCrypt.gensalt()),
          salt = "",
          disabled = false
        )
        newUserId = newUser.id.asInstanceOf[UUID]
        // add grants
        _ = create.rights foreach {
          _.foreach { grant =>
            RightGrantRepository.create(
              grantId = UUID.randomUUID,
              appId = appId,
              groupId = None,
              accountId = Some(newUser.id),
              grantName = grant.grantName,
              grantValue = grant.grantValue
            )
          }
        }
        // add groups
        _ = (create.groups.toSeq.flatten ++ asm.right.toSeq.map {
          _.id.asInstanceOf[UUID]
        }).distinct.map {
          groupId => GroupFactory.addAccountToGroup(accountId = newUserId, groupId = groupId)
        }
      } yield newUser
      newUserTry
    }
  }

  def createGroupInApp(appId: UUID, create: GestaltGroupCreateWithRights)(implicit session: DBSession = autoSession): UserGroupRepository = {
    DB localTx { implicit session =>
      // have to find the default group store for the app
      // then add the group to the directory
      // then associate any rights specified in the request
      if (GestaltAppRepository.find(appId).isEmpty) {
        throw new ResourceNotFoundException(
          resource = s"/apps/${appId}",
          message = "could not create group in non-existent app",
          developerMessage = "Could not create group in non-existent application. If this was created as a result of an attempt to create a group in an org, it suggests that the org is misconfigured."
        )
      }
      val dirId = getDefaultGroupStore(appId).id.asInstanceOf[UUID]
      if (UserGroupRepository.findBy(sqls"name = ${create.name} and dir_id = ${dirId}").isDefined) {
        throw new ConflictException(
          resource = s"/apps/${appId}",
          message = "group name already exists",
          developerMessage = "The default directory associated with this app already contains a group with the specified name."
        )
      }
      val newGroup = UserGroupRepository.create(
        id = UUID.randomUUID(),
        dirId = dirId,
        name = create.name,
        disabled = false
      )
      // add grants
      val newGroupId = newGroup.id.asInstanceOf[UUID]
      create.rights foreach {
        _.foreach { grant =>
          RightGrantRepository.create(
            grantId = UUID.randomUUID,
            appId = appId,
            groupId = Some(newGroupId),
            accountId = None,
            grantName = grant.grantName,
            grantValue = grant.grantValue
          )
        }
      }
      newGroup
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
      dir <- DirectoryFactory.find(dirId) match {
        case Some(dir) => Success(dir)
        case None => Failure(ResourceNotFoundException(
          resource = "",
          message = "could not locate the default account directory for the specified app",
          developerMessage = "Could not locate the default account directory for the specified application."
        ))
      }
      account <- dir.lookupAccountByUsername(username) match {
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
          message = "could not locate service app for the specified org",
          developerMessage = "Could not locate the service app for the specified organization."
        ))
      }
      asm <- getDefaultAccountStore(app.id.asInstanceOf[UUID])
      dirId = asm.fold(_.id,_.dirId).asInstanceOf[UUID]
      dir <- DirectoryFactory.find(dirId) match {
        case Some(dir) => Success(dir)
        case None => Failure(ResourceNotFoundException(
          resource = "",
          message = "could not locate the default account directory for the specified org",
          developerMessage = "Could not locate the default account directory for the specified organization."
        ))
      }
      account <- dir.lookupAccountByUsername(username) match {
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

  def findGroupNameInDefaultGroupStore(appId: UUID, groupName: String)(implicit session: DBSession = autoSession): Option[UserGroupRepository] = {
    DirectoryFactory.find(getDefaultGroupStore(appId).id.asInstanceOf[UUID]) match {
      case Some(dir) => dir.lookupGroupByName(groupName)
      case None => throw new ResourceNotFoundException(
        resource = "",
        message = "could not locate the default account directory for the app/org",
        developerMessage = "Could not locate the default account directory for the application/organization."
      )
    }
  }

}
