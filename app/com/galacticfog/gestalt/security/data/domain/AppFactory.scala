package com.galacticfog.gestalt.security.data.domain

import java.util.UUID

import com.galacticfog.gestalt.security.api.errors.{CreateConflictException, ResourceNotFoundException, UnknownAPIException, BadRequestException}
import com.galacticfog.gestalt.security.api._
import com.galacticfog.gestalt.security.data.model._
import com.galacticfog.gestalt.security.utils.SecureIdGenerator
import org.mindrot.jbcrypt.BCrypt
import scalikejdbc._

import scala.util.{Success, Failure}
import scala.util.matching.Regex

object AppFactory extends SQLSyntaxSupport[UserAccountRepository] {

  override val autoSession = AutoSession

  def create(orgId: UUID, name: String, isServiceOrg: Boolean)(implicit session: DBSession = autoSession) = {
    GestaltAppRepository.create(
      id = UUID.randomUUID(),
      name = name,
      orgId = orgId,
      serviceOrgId = if (isServiceOrg) Some(orgId) else None
    )
  }

  def listAccountStoreMappings(appId: UUID)(implicit session: DBSession = autoSession): Seq[AccountStoreMappingRepository] = {
    AccountStoreMappingRepository.findAllBy(sqls"app_id = ${appId}")
  }

  def mapGroupToApp(appId: UUID, groupId: UUID, defaultAccountStore: Boolean)(implicit session: DBSession = autoSession): AccountStoreMappingRepository = {
    AccountStoreMappingRepository.create(
      id = UUID.randomUUID,
      appId = appId,
      storeType = "GROUP",
      accountStoreId = groupId,
      defaultAccountStore = if (defaultAccountStore) Some(appId) else None,
      defaultGroupStore = None
    )
  }

  def getDefaultGroupStore(appId: UUID)(implicit session: DBSession = autoSession): Option[GestaltDirectoryRepository] = {
    AccountStoreMappingRepository.findBy(sqls"default_group_store = ${appId}") flatMap { asm =>
      asm.storeType match {
        case "GROUP" =>
          throw new BadRequestException(
            resource = s"/apps/${appId}",
            message = "app is configured with an invalid default group store",
            developerMessage = "App is configured with an invalid default group store. The store corresponds to a group, which cannot be used to store groups. The default group store for the app should be removed or changed to correspond to a directory."
          )
        case "DIRECTORY" =>
          GestaltDirectoryRepository.find(asm.accountStoreId)
      }
    }
  }

  def getDefaultAccountStore(appId: UUID)(implicit session: DBSession = autoSession): Either[GestaltDirectoryRepository,UserGroupRepository] = {
    AccountStoreMappingRepository.findBy(sqls"default_account_store = ${appId}") match {
      case Some(asm) =>
        asm.storeType match {
          case "GROUP" =>
            UserGroupRepository.find(asm.accountStoreId) match {
              case Some(group) => Right(group)
              case None =>
                throw new UnknownAPIException(500, resource = s"/groups/${asm.accountStoreId}", message = "error accessing group corresponding to app's default account store", developerMessage = "")
            }
          case "DIRECTORY" =>
            GestaltDirectoryRepository.find(asm.accountStoreId) match {
              case Some(dir) => Left(dir)
              case None =>
                throw new UnknownAPIException(500, resource = s"/directories/${asm.accountStoreId}", message = "error accessing directory corresponding to app's default account store", developerMessage = "")
            }
        }
      case None =>
        throw new BadRequestException(
          resource = s"/apps/${appId}",
          message = "app does not have a default account store",
          developerMessage = "Some operation attempted to use the app's default account store, but it does not have one. You will need to add one or avoid relying on its existence.")
    }
  }

  def createOrgAccountStoreMapping(appId: UUID, create: GestaltOrgAccountStoreMappingCreate)(implicit session: DBSession = autoSession): AccountStoreMappingRepository = {
    if (AccountStoreMappingRepository.findBy(sqls"app_id = ${appId} and store_type = ${create.storeType.label} and account_store_id = ${create.accountStoreId}").isDefined) {
      throw new CreateConflictException(
        resource = s"/apps/${appId}/accountStores",
        message = "mapping already exists",
        developerMessage = "There already exists a mapping on this application/org to the specified group or directory."
      )
    }
    if (create.isDefaultAccountStore && AccountStoreMappingRepository.findBy(sqls"default_account_store = ${appId}").isDefined) {
      throw new CreateConflictException(
        resource = s"/apps/${appId}/accountStores",
        message = "default account store already set",
        developerMessage = "There already exists a default account store on this application/org. This default must be removed before a new one can be set."
      )
    }
    if (create.isDefaultGroupStore && AccountStoreMappingRepository.findBy(sqls"default_group_store = ${appId}").isDefined) {
      throw new CreateConflictException(
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
      throw new CreateConflictException(
        resource = s"/apps/${appId}/accountStores",
        message = "mapping already exists",
        developerMessage = "There already exists a mapping on this application/org to the specified group or directory."
      )
    }
    if (create.isDefaultAccountStore && AccountStoreMappingRepository.findBy(sqls"default_account_store = ${create.appId}").isDefined) {
      throw new CreateConflictException(
        resource = s"/apps/${appId}/accountStores",
        message = "default account store already set",
        developerMessage = "There already exists a default account store on this application/org. This default must be removed before a new one can be set."
      )
    }
    if (create.isDefaultGroupStore && AccountStoreMappingRepository.findBy(sqls"default_group_store = ${create.appId}").isDefined) {
      throw new CreateConflictException(
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

  def createAccountInApp(appId: UUID, create: GestaltAccountCreateWithRights)(implicit session: DBSession = autoSession): UserAccountRepository = {
    DB localTx { implicit session =>
      // have to find the default account store for the app
      // if it's a directory, add the account to the directory
      // if it's a group, add the account to the group's directory and then to the group
      // then add the account to any groups specified in the create request
      if (GestaltAppRepository.find(appId).isEmpty) {
        throw new ResourceNotFoundException(
          resource = s"/apps/${appId}",
          message = "could not create account in non-existent app",
          developerMessage = "Could not create account in non-existent application. If this was created as a result of an attempt to create an account in an org, it suggests that the org is misconfigured."
        )
      }
      val cred = create.credential.asInstanceOf[GestaltPasswordCredential]
      val asm = getDefaultAccountStore(appId)
      val dirId = asm.fold(_.id, _.dirId).asInstanceOf[UUID]
      if (UserAccountRepository.findBy(sqls"username = ${create.username} and dir_id = ${dirId}").isDefined) {
        throw new CreateConflictException(
          resource = s"/apps/${appId}",
          message = "username already exists",
          developerMessage = "The default directory associated with this app already contains an account with the specified username."
        )
      }
      if (UserAccountRepository.findBy(sqls"email = ${create.email} and dir_id = ${dirId}").isDefined) {
        throw new CreateConflictException(
          resource = s"/directories/${dirId}",
          message = "email address already exists",
          developerMessage = "The default directory associated with this app already contains an account with the specified email address."
        )
      }
      val phoneNumber = if (! create.phoneNumber.isEmpty) {
        val newNumber = {
          val t = AccountFactory.validatePhoneNumber(create.phoneNumber)
          t match {
            case Success(canonicalPN) => canonicalPN
            case Failure(ex) => ex match {
              case br: BadRequestException => throw br.copy(
                resource = s"/accounts"
              )
              case t: Throwable => throw t
            }
          }
        }
        if (UserAccountRepository.findBy(sqls"phone_number = ${newNumber} and dir_id = ${dirId}").isDefined) {
          throw new CreateConflictException(
            resource = s"/directories/${dirId}",
            message = "phone number already exists",
            developerMessage = "The default directory associated with this app already contains an account with the specified phone number."
          )
        }
        Some(newNumber)
      } else None
      val newUser = UserAccountRepository.create(
        id = UUID.randomUUID(),
        dirId = dirId,
        username = create.username,
        email = create.email,
        phoneNumber = phoneNumber,
        firstName = create.firstName,
        lastName = create.lastName,
        hashMethod = "bcrypt",
        secret = BCrypt.hashpw(cred.password, BCrypt.gensalt()),
        salt = "",
        disabled = false
      )
      // add grants
      val newUserId = newUser.id.asInstanceOf[UUID]
      create.rights foreach {
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
      (create.groups.toSeq.flatten ++ asm.right.toSeq.map {
        _.id.asInstanceOf[UUID]
      }).distinct.map {
        groupId => GroupFactory.addAccountToGroup(accountId = newUserId, groupId = groupId)
      }
      newUser
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
      val dirId = getDefaultGroupStore(appId) match {
        case Some(dir) => dir.id.asInstanceOf[UUID]
        case None => throw new BadRequestException(
          resource = s"/apps/${appId}",
          message = "specified app does not have a default group store",
          developerMessage = "Specified app does not have a default group store. Please provide one or create the group directly in the desired directory."
        )
      }
      if (UserGroupRepository.findBy(sqls"name = ${create.name} and dir_id = ${dirId}").isDefined) {
        throw new CreateConflictException(
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

}
