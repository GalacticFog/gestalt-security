package com.galacticfog.gestalt.security.data.domain

import com.galacticfog.gestalt.security.api.{GestaltAccountCreate, GestaltDirectoryCreate, GestaltGroupCreate, GestaltPasswordCredential}
import com.galacticfog.gestalt.security.api.errors.{BadRequestException, ConflictException, ResourceNotFoundException, UnknownAPIException}
import org.mindrot.jbcrypt.BCrypt
import org.postgresql.util.PSQLException
import scalikejdbc._
import scalikejdbc.TxBoundary.Try._
import java.util.UUID

import com.galacticfog.gestalt.security.data.model._

import play.api.Logger

import scala.util.{Failure, Success, Try}

trait Directory {
  def createAccount(username: String,
                    description: Option[String],
                    firstName: String,
                    lastName: String,
                    email: Option[String],
                    phoneNumber: Option[String],
                    cred: GestaltPasswordCredential)
                   (implicit session: DBSession = AutoSession): Try[UserAccountRepository]

  def authenticateAccount(account: UserAccountRepository, plaintext: String)
                         (implicit session: DBSession = AutoSession): Boolean

  def lookupAccountByUsername(username: String)
                             (implicit session: DBSession = AutoSession): Option[UserAccountRepository]
  def lookupGroupByName(groupName: String)
                       (implicit session: DBSession = AutoSession): Option[UserGroupRepository]

  def disableAccount(accountId: UUID)
                    (implicit session: DBSession = AutoSession): Unit

  def deleteGroup(uuid: UUID)
                 (implicit session: DBSession = AutoSession): Boolean

  def getGroupById(groupId: UUID)
                  (implicit session: DBSession = AutoSession): Option[UserGroupRepository]

  def listGroupAccounts(groupId: UUID)
                       (implicit session: DBSession = AutoSession): Seq[UserAccountRepository]

  def listOrgGroupsByName(orgId: UUID, groupName: String)
                         (implicit session: DBSession = AutoSession): Seq[UserGroupRepository]

  def id: UUID
  def name: String
  def description: Option[String]
  def orgId: UUID

}

case class InternalDirectory(daoDir: GestaltDirectoryRepository) extends Directory {
  override def id: UUID = daoDir.id.asInstanceOf[UUID]
  override def name: String = daoDir.name
  override def orgId: UUID = daoDir.orgId.asInstanceOf[UUID]
  override def description: Option[String] = daoDir.description

  override def disableAccount(accountId: UUID)(implicit session: DBSession): Unit = {
    AccountFactory.disableAccount(accountId)
  }

  override def authenticateAccount(account: UserAccountRepository, plaintext: String)(implicit session: DBSession): Boolean = {
    AccountFactory.checkPassword(account, plaintext)
  }

  override def lookupAccountByUsername(username: String)(implicit session: DBSession): Option[UserAccountRepository] = AccountFactory.directoryLookup(id, username)

  override def lookupGroupByName(groupName: String)(implicit session: DBSession): Option[UserGroupRepository] = UserGroupRepository.findBy(sqls"dir_id = ${id} and name = ${groupName}")

  override def getGroupById(groupId: UUID)(implicit session: DBSession) = {
    GroupFactory.find(groupId) flatMap { grp =>
      if (grp.dirId == id) Some(grp)
      else None
    }
  }

  override def listGroupAccounts(groupId: UUID)(implicit session: DBSession): Seq[UserAccountRepository] = GroupFactory.listGroupAccounts(groupId)

  override def deleteGroup(groupId: UUID)(implicit session: DBSession): Boolean = {
    GroupFactory.delete(groupId)
  }

  override def createAccount(username: String, description: Option[String], firstName: String, lastName: String, email: Option[String], phoneNumber: Option[String], cred: GestaltPasswordCredential)(implicit session: DBSession): Try[UserAccountRepository] = {
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

  override def listOrgGroupsByName(orgId: UUID, groupName: String)(implicit session: DBSession): Seq[UserGroupRepository] = {
    UserGroupRepository.findAllBy(sqls"org_id = ${orgId} and name = ${groupName}")
  }
}


object DirectoryFactory extends SQLSyntaxSupport[GestaltDirectoryRepository] {

  implicit def toDirFromDAO(daoDir: GestaltDirectoryRepository): Directory = {
    daoDir.directoryType.toUpperCase match {
      case "INTERNAL" => InternalDirectory(daoDir)
      case "LDAP" =>
        // TODO - Use plugin / adapter to get classname
        val ldapClass = Class.forName("com.galacticfog.gestalt.security.data.domain.LDAPDirectory").getConstructors.head
        ldapClass.newInstance(daoDir, AccountFactory.instance, GroupFactory.instance).asInstanceOf[LDAPDirectory]
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

  def createDirectory(orgId: UUID, create: GestaltDirectoryCreate)(implicit session: DBSession = autoSession): Try[Directory] = {
    Try {
      GestaltDirectoryRepository.create(
        id = UUID.randomUUID(),
        orgId = orgId,
        name = create.name,
        description = create.description,
        config = create.config map {_.toString},
        directoryType = create.directoryType.label
      )
    } recoverWith {
      case t: PSQLException if (t.getSQLState == "23505" || t.getSQLState == "23514") =>
        t.getServerErrorMessage.getConstraint match {
          case "directory_name_org_id_key" => Failure(ConflictException(
            resource = s"/orgs/${orgId}/directories",
            message = "directory with specified name already exists in org",
            developerMessage = "The org already contains a directory with the specified name."
          ))
          case _ => Failure(t)
        }
    } map {d => d: Directory}
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
        description = create.description,
        dirId = dir.id.asInstanceOf[UUID],
        maybeParentOrg = Some(dir.orgId.asInstanceOf[UUID])
      )
    }
  }

  def listByOrgId(orgId: UUID)(implicit session: DBSession = autoSession): List[Directory] = {
    GestaltDirectoryRepository.findAllBy(sqls"org_id=${orgId}") map {d => d:Directory}
  }

}
