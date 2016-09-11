package com.galacticfog.gestalt.security.data.domain

import java.util.UUID

import com.galacticfog.gestalt.security.api.errors.ResourceNotFoundException
import com.galacticfog.gestalt.security.api._
import com.galacticfog.gestalt.security.data.APIConversions
import com.galacticfog.gestalt.security.data.model.{GestaltDirectoryRepository, UserAccountRepository}
import com.galacticfog.gestalt.security.plugins.DirectoryPlugin
import org.mindrot.jbcrypt.BCrypt
import play.api.Logger

import scala.util.Try

case class InternalDirectory(directory: GestaltDirectoryRepository) extends DirectoryPlugin {
  override def id: UUID = directory.id.asInstanceOf[UUID]
  override def name: String = directory.name
  override def orgId: UUID = directory.orgId.asInstanceOf[UUID]
  override def description: Option[String] = directory.description

  override def disableAccount(accountId: UUID, disabled: Boolean = true): Unit = {
    AccountFactory.disableAccount(accountId, disabled)
  }

  override def authenticateAccount(account: GestaltAccount, plaintext: String): Boolean = {
    UserAccountRepository.find(account.id) match {
      case Some(uar) => AccountFactory.checkPassword(uar, plaintext)
      case None =>
        Logger.warn(s"LDAPDirectory.authenticateAccount account not found ${account.id}")
        false
    }
  }

  override def updateAccount(account: GestaltAccount, update: GestaltAccountUpdate): Try[GestaltAccount] = {
    // Currently not called.  TODO - need to address how to update disabled attribute since neither account or update contain it.
    UserAccountRepository.find(account.id) match {
      case Some(uar) => AccountFactory.saveAccount(uar).map { APIConversions.accountModelToApi(_) }
      case None => throw new ResourceNotFoundException(resource = account.id.toString, message = "Error finding account for update", developerMessage = "Error finding repository account for update")
    }
  }

  override def lookupGroups(groupName: String): Seq[GestaltGroup] = {
    GroupFactory.queryShadowedDirectoryGroups(Some(id), Some(groupName)).map { APIConversions.groupModelToApi(_) }
  }

  override def getGroupById(groupId: UUID): Option[GestaltGroup] = {
    GroupFactory.find(groupId) flatMap { grp =>
      if (grp.dirId == id) Some(APIConversions.groupModelToApi(grp))
      else None
    }
  }

  override def listGroupAccounts(groupId: UUID): Seq[GestaltAccount] = GroupFactory.listGroupAccounts(groupId).map { APIConversions.accountModelToApi(_) }

  override def deleteGroup(groupId: UUID): Boolean = {
    GroupFactory.delete(groupId)
  }

  override def createAccount(username: String, description: Option[String], firstName: String, lastName: String, email: Option[String], phoneNumber: Option[String], cred: GestaltPasswordCredential): Try[GestaltAccount] = {
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
    ).map { APIConversions.accountModelToApi(_) }
  }

  override def createGroup(name: String, description: Option[String]): Try[GestaltGroup] = {
    GroupFactory.create(
      name = name,
      description = description,
      dirId = id,
      maybeParentOrg = None
    ).map { APIConversions.groupModelToApi(_) }
  }

  /** Directory-specific (i.e., deep) query of accounts, supporting wildcard matches on username, phone number or email address.
    *
    * Wildcard character '*' matches any number of characters; multiple wildcards may be present at any location in the query string.
    *
    * @param group    optional group search (no wildcard matching)
    * @param username username query parameter (e.g., "*smith")
    * @param phone    phone number query parameter (e.g., "+1505*")
    * @param email    email address query parameter (e.g., "*smith@company.com")
    * @return List of matching accounts (matching the query strings and belonging to the specified group)
    */
  override def lookupAccounts(group: Option[GestaltGroup],
                              username: Option[String],
                              phone: Option[String],
                              email: Option[String]): Seq[GestaltAccount] = {
    AccountFactory.queryShadowedDirectoryAccounts(
      dirId = Some(id),
      nameQuery = username,
      phoneQuery = phone,
      emailQuery = email
    ).map { APIConversions.accountModelToApi(_) }
  }
}
