package com.galacticfog.gestalt.security.data.domain

import java.util.UUID

import com.galacticfog.gestalt.security.api.GestaltPasswordCredential
import com.galacticfog.gestalt.security.data.model.{UserGroupRepository, UserAccountRepository, GestaltDirectoryRepository}
import org.mindrot.jbcrypt.BCrypt
import play.api.Logger
import scalikejdbc.DBSession

import scala.util.Try

case class InternalDirectory(daoDir: GestaltDirectoryRepository) extends Directory {
  override def id: UUID = daoDir.id.asInstanceOf[UUID]
  override def name: String = daoDir.name
  override def orgId: UUID = daoDir.orgId.asInstanceOf[UUID]
  override def description: Option[String] = daoDir.description

  override def disableAccount(accountId: UUID, disabled: Boolean = true)(implicit session: DBSession): Unit = {
    AccountFactory.disableAccount(accountId, disabled)
  }

  override def authenticateAccount(account: UserAccountRepository, plaintext: String)(implicit session: DBSession): Boolean = {
    if (account.disabled) Logger.warn(s"LDAPDirectory.authenticateAccount called against disabled account ${account.id}")
    AccountFactory.checkPassword(account, plaintext)
  }

  override def lookupGroups(groupName: String)
                           (implicit session: DBSession): Seq[UserGroupRepository] =
    GroupFactory.queryShadowedDirectoryGroups(Some(id), Some(groupName))

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

  override def createGroup(name: String, description: Option[String])
                          (implicit session: DBSession): Try[UserGroupRepository] = {
    GroupFactory.create(
      name = name,
      description = description,
      dirId = id,
      maybeParentOrg = None
    )
  }

  /** Directory-specific (i.e., deep) query of accounts, supporting wildcard matches on username, phone number or email address.
    *
    * Wildcard character '*' matches any number of characters; multiple wildcards may be present at any location in the query string.
    *
    * @param group    optional group search (no wildcard matching)
    * @param username username query parameter (e.g., "*smith")
    * @param phone    phone number query parameter (e.g., "+1505*")
    * @param email    email address query parameter (e.g., "*smith@company.com")
    * @param session  database session (optional)
    * @return List of matching accounts (matching the query strings and belonging to the specified group)
    */
  override def lookupAccounts(group: Option[UserGroupRepository],
                              username: Option[String],
                              phone: Option[String],
                              email: Option[String])
                             (implicit session: DBSession): Seq[UserAccountRepository] = {
    AccountFactory.queryShadowedDirectoryAccounts(
      dirId = Some(id),
      nameQuery = username,
      phoneQuery = phone,
      emailQuery = email
    )
  }
}
