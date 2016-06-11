package com.galacticfog.gestalt.security.data.domain

import com.galacticfog.gestalt.security.api.{GestaltAccountCreate, GestaltDirectoryCreate, GestaltGroupCreate, GestaltPasswordCredential}
import com.galacticfog.gestalt.security.api.errors.{BadRequestException, ConflictException, ResourceNotFoundException}
import org.postgresql.util.PSQLException
import scalikejdbc._
import scalikejdbc.TxBoundary.Try._
import java.util.UUID

import com.galacticfog.gestalt.security.data.model._

import scala.util.{Failure, Try}

trait Directory {

  def createAccount(username: String,
                    description: Option[String],
                    firstName: String,
                    lastName: String,
                    email: Option[String],
                    phoneNumber: Option[String],
                    cred: GestaltPasswordCredential)
                   (implicit session: DBSession = AutoSession): Try[UserAccountRepository]

  def createGroup(name: String,
                  description: Option[String])
                 (implicit session: DBSession = AutoSession): Try[UserGroupRepository]

  def authenticateAccount(account: UserAccountRepository, plaintext: String)
                         (implicit session: DBSession = AutoSession): Boolean

  /** Directory-specific (i.e., deep) query of accounts, supporting wildcard matches on username, phone number or email address.
    *
    * Wildcard character '*' matches any number of characters; multiple wildcards may be present at any location in the query string.
    *
    * @param group  optional group search (no wildcard matching)
    * @param username username query parameter (e.g., "*smith")
    * @param phone phone number query parameter (e.g., "+1505*")
    * @param email email address query parameter (e.g., "*smith@company.com")
    * @param session database session (optional)
    * @return List of matching accounts (matching the query strings and belonging to the specified group)
    */
  def lookupAccounts(group: Option[UserGroupRepository] = None,
                     username: Option[String] = None,
                     phone: Option[String] = None,
                     email: Option[String] = None)
                    (implicit session: DBSession = AutoSession): Seq[UserAccountRepository]

  /**
    * Directory-specific (i.e., deep) query of groups, supporting wildcard matches on group name.
    *
    * Wildcard character '*' matches any number of character; multiple wildcards may be present at any location in the query string.
    *
    * @param groupName group name query parameter (e.g., "*-admins")
    * @param session database session (optional)
    * @return List of matching groups
    */
  def lookupGroups(groupName: String)
                  (implicit session: DBSession = AutoSession): Seq[UserGroupRepository]

  def disableAccount(accountId: UUID, disabled: Boolean = true)
                    (implicit session: DBSession = AutoSession): Unit

  def deleteGroup(uuid: UUID)
                 (implicit session: DBSession = AutoSession): Boolean

  def getGroupById(groupId: UUID)
                  (implicit session: DBSession = AutoSession): Option[UserGroupRepository]

  def listGroupAccounts(groupId: UUID)
                       (implicit session: DBSession = AutoSession): Seq[UserAccountRepository]

  def id: UUID
  def name: String
  def description: Option[String]
  def orgId: UUID

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
      case Some(dir) => dir.createGroup(
        name = create.name,
        description = create.description
      )
    }
  }

  def listByOrgId(orgId: UUID)(implicit session: DBSession = autoSession): List[Directory] = {
    GestaltDirectoryRepository.findAllBy(sqls"org_id=${orgId}") map {d => d:Directory}
  }

}
