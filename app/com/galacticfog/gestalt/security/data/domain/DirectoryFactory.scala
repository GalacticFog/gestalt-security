package com.galacticfog.gestalt.security.data.domain

import com.galacticfog.gestalt.security.api.{GestaltGroupCreate, GestaltDirectoryCreate, GestaltPasswordCredential, GestaltAccountCreate}
import com.galacticfog.gestalt.security.api.errors.{UnknownAPIException, BadRequestException, CreateConflictException, ResourceNotFoundException}
import org.mindrot.jbcrypt.BCrypt
import play.api.libs.json.Json
import scalikejdbc._
import java.util.UUID
import com.galacticfog.gestalt.security.data.model._

trait Directory {


  def findByUsername(username: String): Option[UserAccountRepository]

  def id: UUID
  def name: String
  def description: Option[String]
  def orgId: UUID

  def disableAccount(accountId: UUID): Unit
  def deleteGroup(uuid: UUID): Boolean

  def getGroupById(groupId: UUID): Option[UserGroupRepository]

  def listGroupAccounts(groupId: UUID): Seq[UserAccountRepository]
}

case class InternalDirectory(daoDir: GestaltDirectoryRepository) extends Directory {
  override def id: UUID = daoDir.id.asInstanceOf[UUID]
  override def name: String = daoDir.name
  override def orgId: UUID = daoDir.orgId.asInstanceOf[UUID]
  override def description: Option[String] = daoDir.description

  override def disableAccount(accountId: UUID): Unit = {
    AccountFactory.disableAccount(accountId)
  }

  override def findByUsername(username: String): Option[UserAccountRepository] = AccountFactory.directoryLookup(id, username)

  override def getGroupById(groupId: UUID) = {
    GroupFactory.find(groupId) flatMap { grp =>
      if (grp.dirId == id) Some(grp)
      else None
    }
  }

  override def listGroupAccounts(groupId: UUID): Seq[UserAccountRepository] = GroupFactory.listGroupAccounts(groupId)

  override def deleteGroup(groupId: UUID): Boolean = {
    GroupFactory.delete(groupId)
  }
}

object DirectoryFactory extends SQLSyntaxSupport[GestaltDirectoryRepository] {

  implicit def toDirFromDAO(daoDir: GestaltDirectoryRepository): Directory = {
    daoDir.directoryType.toUpperCase match {
      case "INTERNAL" => InternalDirectory(daoDir)
      case _ => throw new BadRequestException(
        resource = s"/directories/${daoDir.id}",
        message = "invalid directory type",
        developerMessage = "The requested directory has an unsupported directory type. Please ensure that you are running the latest version and contact support."
      )
    }

  }

  override val autoSession = AutoSession

  def find(dirId: UUID)(implicit session: DBSession = autoSession): Option[Directory] = {
    GestaltDirectoryRepository.find(dirId) map {d => d:Directory}
  }

  def findAll(implicit session: DBSession = autoSession): List[Directory] = {
    GestaltDirectoryRepository.findAll map {d => d:Directory}
  }

  def createDirectory(orgId: UUID, create: GestaltDirectoryCreate)(implicit session: DBSession = autoSession): Directory = {
    if (GestaltDirectoryRepository.findBy(sqls"name = ${create.name} and org_id = ${orgId}").isDefined) {
      throw new CreateConflictException(
        resource = s"/orgs/${orgId}/directories",
        message = "directory with specified name already exists in org",
        developerMessage = "The org already contains a directory with the specified name."
      )
    }
    val directoryType = {
      create.config flatMap {c => (c \ "directoryType").asOpt[String]} match {
        case None =>
          GestaltDirectoryTypeRepository.findBy(sqls"UPPER(name) = UPPER('INTERNAL')") match {
            case None => throw new UnknownAPIException(
              code = 500,
              resource = s"/orgs/${orgId}/directories",
              message = "default directory type INTERNAL not found",
              developerMessage = "A directory type not specified during directory create request, and the default directory type was not available."
            )
            case Some(dirType) => dirType.name.toUpperCase
          }
        case Some(createType) =>
          GestaltDirectoryTypeRepository.findBy(sqls"UPPER(name) = UPPER(${createType})") match {
            case None => throw new BadRequestException(
              resource = s"/orgs/${orgId}/directories",
              message = "directory type not valid",
              developerMessage = "During directory creation, the requested directory type was not valid."
            )
            case Some(dirType) => dirType.name.toUpperCase
          }
      }
    }
    GestaltDirectoryRepository.create(
      id = UUID.randomUUID(),
      orgId = orgId,
      name = create.name,
      description = create.description,
      config = create.config map {_.toString},
      directoryType = directoryType
    )
  }

  def createAccountInDir(dirId: UUID, create: GestaltAccountCreate)(implicit session: DBSession = autoSession): UserAccountRepository = {
    if (GestaltDirectoryRepository.find(dirId).isEmpty) {
      throw new ResourceNotFoundException(
        resource = s"/directories/${dirId}",
        message = "could not create account in non-existent directory",
        developerMessage = "Could not create account in non-existent directory. If this error was encountered during an attempt to create an account in an org, it suggests that the org is misconfigured."
      )
    }
    if (UserAccountRepository.findBy(sqls"username = ${create.username} and dir_id = ${dirId}").isDefined) {
      throw new CreateConflictException(
        resource = s"/directories/${dirId}/accounts",
        message = "username already exists in directory",
        developerMessage = "The directory already contains an account with the specified username."
      )
    }
    val email = if (! create.email.isEmpty) {
      if (UserAccountRepository.findBy(sqls"phone_number = ${create.email} and dir_id = ${dirId}").isDefined) {
        throw new CreateConflictException(
          resource = s"/directories/${dirId}",
          message = "email address already exists",
          developerMessage = "The directory already contains an account with the specified email address."
        )
      }
      Some(create.email)
    } else None
    val cred = create.credential.asInstanceOf[GestaltPasswordCredential]
    UserAccountRepository.create(
      id = UUID.randomUUID(),
      dirId = dirId,
      username = create.username,
      email = email,
      phoneNumber = if (create.phoneNumber.isEmpty) None else Some(create.phoneNumber),
      firstName = create.firstName,
      lastName = create.lastName,
      hashMethod = "bcrypt",
      secret = BCrypt.hashpw(cred.password, BCrypt.gensalt()),
      salt = "",
      disabled = false
    )
  }

  def createGroupInDir(dirId: UUID, create: GestaltGroupCreate)(implicit session: DBSession = autoSession): UserGroupRepository = {
    if (GestaltDirectoryRepository.find(dirId).isEmpty) {
      throw new ResourceNotFoundException(
        resource = s"/directories/${dirId}",
        message = "could not create group in non-existent directory",
        developerMessage = "Could not create group in non-existent directory. If this error was encountered during an attempt to create a group in an org, it suggests that the org is misconfigured."
      )
    }
    if (UserGroupRepository.findBy(sqls"name = ${create.name} and dir_id = ${dirId}").isDefined) {
      throw new CreateConflictException(
        resource = s"/directories/${dirId}/groups",
        message = "group name already exists in directory",
        developerMessage = "The directory already contains a group with the specified name."
      )
    }
    UserGroupRepository.create(
      id = UUID.randomUUID(),
      dirId = dirId,
      name = create.name,
      disabled = false
    )
  }

  def listByOrgId(orgId: UUID)(implicit session: DBSession = autoSession): List[Directory] = {
    GestaltDirectoryRepository.findAllBy(sqls"org_id=${orgId}") map {d => d:Directory}
  }

}
