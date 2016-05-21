package com.galacticfog.gestalt.security.data.domain

import com.galacticfog.gestalt.security.api.{GestaltAccountCreate, GestaltDirectoryCreate, GestaltGroupCreate, GestaltPasswordCredential}
import com.galacticfog.gestalt.security.api.errors.{BadRequestException, ConflictException, ResourceNotFoundException, UnknownAPIException}
import org.mindrot.jbcrypt.BCrypt
import play.api.libs.json.{JsObject, JsValue, Json}
import scalikejdbc._
import scalikejdbc.TxBoundary.Try._
import java.util.UUID
import javax.naming.NamingEnumeration
import javax.naming.directory.{SearchControls, SearchResult}

import com.galacticfog.gestalt.security.data.model._
import org.apache.shiro.SecurityUtils
import org.apache.shiro.{authc => shiroauthc}
import shiroauthc.UsernamePasswordToken
import org.apache.shiro.mgt.DefaultSecurityManager
import org.apache.shiro.realm.activedirectory.ActiveDirectoryRealm
import org.apache.shiro.realm.ldap.JndiLdapContextFactory
import org.apache.shiro.subject.Subject
import play.api.Logger

import scala.util.{Failure, Success, Try}

trait Directory {
  def createAccount(username: String,
                    description: Option[String],
                    firstName: String,
                    lastName: String,
                    email: Option[String],
                    phoneNumber: Option[String],
                    cred: GestaltPasswordCredential): Try[UserAccountRepository]

  def authenticateAccount(account: UserAccountRepository, plaintext: String): Boolean
  def lookupAccountByPrimary(primary: String): Option[UserAccountRepository]
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

  override def authenticateAccount(account: UserAccountRepository, plaintext: String): Boolean = {
    account.hashMethod match {
      case "bcrypt" => BCrypt.checkpw(plaintext, account.secret)
      case "" => account.secret.equals(plaintext)
      case s: String =>
        Logger.warn("Unsupported password hash method: " + s)
        false
    }
  }

  override def lookupAccountByPrimary(primary: String): Option[UserAccountRepository] = None


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

case class LDAPDirectory(daoDir: GestaltDirectoryRepository) extends Directory {
  override def id: UUID = daoDir.id.asInstanceOf[UUID]
  override def name: String = daoDir.name
  override def orgId: UUID = daoDir.orgId.asInstanceOf[UUID]
  override def description: Option[String] = daoDir.description

  val config = Json.parse(daoDir.config.getOrElse("{}"))
  val activeDirectory = (config \ "activeDirectory").asOpt[Boolean].getOrElse(false)
  val url = (config \ "url").asOpt[String].getOrElse("")
  val searchBase = (config \ "searchBase").asOpt[String].getOrElse("")
  val systemUsername = (config \ "systemUsername").asOpt[String].getOrElse("")
  val systemPassword = (config \ "systemPassword").asOpt[String].getOrElse("")
  var connectionTimeout = (config \ "connectionTimeout").asOpt[Int]
  var globalSessionTimeout = (config \ "globalSessionTimeout").asOpt[Int]
  var credentialsMatcher = (config \ "credentialsMatcher").asOpt[String]
  var authenticationMechanism = (config \ "authenticationMechanism").asOpt[String]
  var primaryField = (config \ "primaryField").asOpt[String].getOrElse("uid")

  val ldapRealm = new ActiveDirectoryRealm()
  ldapRealm.setUrl(url)
  ldapRealm.setSearchBase(searchBase)
  ldapRealm.setSystemUsername(systemUsername)
  ldapRealm.setSystemPassword(systemPassword)
  val securityMgr = new DefaultSecurityManager(ldapRealm)
  SecurityUtils.setSecurityManager(securityMgr)

  override def authenticateAccount(account: UserAccountRepository, plaintext: String): Boolean = {
    var result = true
    val sep = if (searchBase.startsWith(",")) "" else ","
    val dn = primaryField + "=" + account.username + sep + searchBase
    val token = new UsernamePasswordToken(dn, plaintext)
    try {
      val subject: Subject = SecurityUtils.getSubject
      subject.login(token)
    } catch {
      case _: Throwable => result = false
        if (url == "" || searchBase == "" || systemUsername == "" || systemPassword == "") {
          Logger.error("Error: LDAP configuration was not setup.")
        }
    }
    result
  }

  override def lookupAccountByPrimary(primary: String): Option[UserAccountRepository] = {
    try {
      val contextFactory = new JndiLdapContextFactory()
      contextFactory.setUrl(url)
      val sep = if (searchBase.startsWith(",")) "" else ","
      val dn = "cn=" + systemUsername + sep + searchBase
      contextFactory.setSystemUsername(systemUsername)
      contextFactory.setSystemPassword(systemPassword)
      val context = contextFactory.getLdapContext(dn.asInstanceOf[AnyRef], systemPassword.asInstanceOf[AnyRef])
      val constraints = new SearchControls()
      constraints.setSearchScope(SearchControls.SUBTREE_SCOPE)
      val answer: NamingEnumeration[SearchResult] = context.search(searchBase, primaryField + "=" + primary, constraints)

      if (answer.hasMore()) {
        val current = answer.next()
        val attrs = current.getAttributes
        val username = primary
        val firstname = if (attrs.get("firstName") != null) attrs.get("firstName").toString else ""
        val lastname = if (attrs.get("lastName") != null) attrs.get("lastName").toString else ""
        val description = if (attrs.get("description") != null) Some(attrs.get("description").toString) else None
        val email = if (attrs.get("mail") != null) Some(attrs.get("mail").toString) else None
        val phone = if (attrs.get("phoneNumber") != null) Some(attrs.get("phoneNumber").toString) else None
        // Create subject in shadow directory for auth
        for {
          account <- this.shadowAccount(username, description, firstname, lastname, email, phone, GestaltPasswordCredential("Notused")).toOption
        } yield account
      } else {
        None
      }
    } catch {
      case e: Throwable =>
        if (url == "" || searchBase == "" || systemUsername == "" || systemPassword == "") {
          Logger.error("Error: LDAP configuration was not setup.")
        }
        Logger.error("Error: Error accessing LDAP.")
        throw e
    }
  }

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
    Failure(BadRequestException(
      resource = s"/orgs/${orgId}/directories/${id}/account",
      message = "Account create request not valid",
      developerMessage = "LDAP Directory does not support account creation."
    ))
  }

  def shadowAccount(username: String, description: Option[String], firstName: String, lastName: String, email: Option[String], phoneNumber: Option[String], cred: GestaltPasswordCredential): Try[UserAccountRepository] = {
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
      case "LDAP" => LDAPDirectory(daoDir)
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
          GestaltDirectoryTypeRepository.findBy(sqls"UPPER(name) = UPPER(${create.directoryType.label})") match {
            case None => Failure(BadRequestException(
              resource = s"/orgs/${orgId}/directories",
              message = s"directory type ${create.directoryType} not valid",
              developerMessage = s"During directory creation, the requested directory type ${create.directoryType} was not valid."
            ))
            case Some(dirType) => Success(dirType.name.toUpperCase)
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
        description = create.description,
        dirId = dir.id.asInstanceOf[UUID],
        parentOrg = dir.orgId.asInstanceOf[UUID]
      )
    }
  }

  def listByOrgId(orgId: UUID)(implicit session: DBSession = autoSession): List[Directory] = {
    GestaltDirectoryRepository.findAllBy(sqls"org_id=${orgId}") map {d => d:Directory}
  }

}
