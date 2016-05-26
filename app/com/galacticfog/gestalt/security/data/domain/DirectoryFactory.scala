package com.galacticfog.gestalt.security.data.domain

import com.galacticfog.gestalt.security.api.{GestaltGroupCreate, GestaltDirectoryCreate, GestaltPasswordCredential, GestaltAccountCreate}
import com.galacticfog.gestalt.security.api.errors.{UnknownAPIException, BadRequestException, ConflictException, ResourceNotFoundException}
import org.mindrot.jbcrypt.BCrypt
import play.api.libs.json.Json
import scalikejdbc._
import scalikejdbc.TxBoundary.Try._
import java.util.UUID
import com.galacticfog.gestalt.security.data.model._

import scala.util.{Success, Failure, Try}

trait Directory {
  def createAccount(username: String,
                    description: Option[String],
                    firstName: String,
                    lastName: String,
                    email: Option[String],
                    phoneNumber: Option[String],
                    cred: GestaltPasswordCredential): Try[UserAccountRepository]

  def lookupAccountByUsername(username: String): Option[UserAccountRepository]
  def lookupGroupByName(groupName: String): Option[UserGroupRepository]

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

  override def lookupAccountByUsername(username: String): Option[UserAccountRepository] = AccountFactory.directoryLookup(id, username)

  override def lookupGroupByName(groupName: String): Option[UserGroupRepository] = GroupFactory.directoryLookup(id, groupName)

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

  override def createAccount(username: String, description: Option[String], firstName: String, lastName: String, email: Option[String], phoneNumber: Option[String], cred: GestaltPasswordCredential): Try[UserAccountRepository] = {
    AccountFactory.createAccount(
      dirId = id,
      username = username,
      description = description,
      firstName = firstName,
      lastName = lastName,
      email = email,
      phoneNumber = phoneNumber,
      hashMethod = "bcrypt",
      salt = "",
      secret = BCrypt.hashpw(cred.password, BCrypt.gensalt()),
      disabled = false
    )
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

  def removeDirectory(dirId: UUID)(implicit session: DBSession = autoSession): Unit = {
    GestaltDirectoryRepository.find(dirId) match {
      case Some(dir) => dir.destroy()
      case _ =>
    }
  }

  // TODO: omg, refactor this
  def createDirectory(orgId: UUID, create: GestaltDirectoryCreate)(implicit session: DBSession = autoSession): Try[Directory] = {
    GestaltDirectoryRepository.findBy(sqls"name = ${create.name} and org_id = ${orgId}") match {
      case Some(_) => Failure(ConflictException(
        resource = s"/orgs/${orgId}/directories",
        message = "directory with specified name already exists in org",
        developerMessage = "The org already contains a directory with the specified name."
      ))
      case None =>
        {
          create.config flatMap {c => (c \ "directoryType").asOpt[String]} match {
            case None =>
              GestaltDirectoryTypeRepository.findBy(sqls"UPPER(name) = UPPER('INTERNAL')") match {
                case None => Failure(UnknownAPIException(
                  code = 500,
                  resource = s"/orgs/${orgId}/directories",
                  message = "default directory type INTERNAL not found",
                  developerMessage = "A directory type not specified during directory create request, and the default directory type was not available."
                ))
                case Some(dirType) => Success(dirType.name.toUpperCase)
              }
            case Some(createType) =>
              GestaltDirectoryTypeRepository.findBy(sqls"UPPER(name) = UPPER(${createType})") match {
                case None => Failure(BadRequestException(
                  resource = s"/orgs/${orgId}/directories",
                  message = "directory type not valid",
                  developerMessage = "During directory creation, the requested directory type was not valid."
                ))
                case Some(dirType) => Success(dirType.name.toUpperCase)
              }
          }
        } map { directoryType =>
          GestaltDirectoryRepository.create(
            id = UUID.randomUUID(),
            orgId = orgId,
            name = create.name,
            description = create.description,
            config = create.config map {_.toString},
            directoryType = directoryType
          )
        }
    }
  }

  def createAccountInDir(dirId: UUID, create: GestaltAccountCreate)(implicit session: DBSession = autoSession): Try[UserAccountRepository] = {
    DirectoryFactory.find(dirId) match {
      case None => Failure(ResourceNotFoundException(
        resource = s"/directories/${dirId}",
        message = "could not create account in non-existent directory",
        developerMessage = "Could not create account in non-existent directory. If this error was encountered during an attempt to create an account in an org, it suggests that the org is misconfigured."
      ))
      case Some(dir) =>
        DB localTx { implicit session =>
          for {
            cred <- Try(create.credential.asInstanceOf[GestaltPasswordCredential])
            newAccount <- dir.createAccount(
              username = create.username,
              description = create.description,
              email = create.email.map(_.trim).filter(!_.isEmpty),
              phoneNumber = create.phoneNumber.map(_.trim).filter(!_.isEmpty),
              firstName = create.firstName,
              lastName = create.lastName,
              cred = cred
            )
            _ = create.groups.toSeq.flatten foreach {
              grpId => GroupMembershipRepository.create(
                accountId = newAccount.id.asInstanceOf[UUID],
                groupId = grpId
              )
            }
          } yield newAccount
        }
    }
  }

  def createGroupInDir(dirId: UUID, create: GestaltGroupCreate)(implicit session: DBSession = autoSession): Try[UserGroupRepository] = {
    GestaltDirectoryRepository.find(dirId) match {
      case None => Failure(ResourceNotFoundException(
        resource = s"/directories/${dirId}",
        message = "could not create group in non-existent directory",
        developerMessage = "Could not create group in non-existent directory. If this error was encountered during an attempt to create a group in an org, it suggests that the org is misconfigured."
      ))
      case Some(dir) => GroupFactory.create(
        name = create.name,
        dirId = dir.id.asInstanceOf[UUID],
        parentOrg = dir.orgId.asInstanceOf[UUID],
        description = create.description
      )
    }
  }

  def listByOrgId(orgId: UUID)(implicit session: DBSession = autoSession): List[Directory] = {
    GestaltDirectoryRepository.findAllBy(sqls"org_id=${orgId}") map {d => d:Directory}
  }

}
