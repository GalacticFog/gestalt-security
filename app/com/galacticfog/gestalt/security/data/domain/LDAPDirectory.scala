package com.galacticfog.gestalt.security.data.domain

import java.util.UUID
import javax.naming.NamingEnumeration
import javax.naming.directory.{SearchControls, SearchResult}

import com.galacticfog.gestalt.security.api.GestaltPasswordCredential
import com.galacticfog.gestalt.security.api.errors.BadRequestException
import com.galacticfog.gestalt.security.data.model._
import org.apache.shiro.SecurityUtils
import org.apache.shiro.authc.UsernamePasswordToken
import org.apache.shiro.mgt.DefaultSecurityManager
import org.apache.shiro.realm.activedirectory.ActiveDirectoryRealm
import org.apache.shiro.realm.ldap.JndiLdapContextFactory
import org.apache.shiro.subject.Subject
import org.mindrot.jbcrypt.BCrypt
import play.api.Logger
import play.api.libs.json.Json
import scalikejdbc._

import scala.util.{Failure, Success, Try}


case class LDAPDirectory(daoDir: GestaltDirectoryRepository, accountFactory: AccountFactoryDelegate, groupFactory: GroupFactoryDelegate) extends Directory {
  override def id: UUID = daoDir.id.asInstanceOf[UUID]
  override def name: String = daoDir.name
  override def orgId: UUID = daoDir.orgId.asInstanceOf[UUID]
  override def description: Option[String] = daoDir.description

  val autoSession = AutoSession
  val session = autoSession
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
  var descField = (config \ "descriptionField").asOpt[String].getOrElse("description")
  var firstnameField = (config \ "firstNameField").asOpt[String].getOrElse("firstName")
  var lastnameField = (config \ "lastNameField").asOpt[String].getOrElse("lastName")
  var emailField = (config \ "emailField").asOpt[String].getOrElse("mail")
  var phoneField = (config \ "phoneNumberField").asOpt[String].getOrElse("phoneNumber")
  var groupField = (config \ "groupField").asOpt[String].getOrElse("ou")
  var memberField = (config \ "memberField").asOpt[String].getOrElse("uniqueMember")
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
    Logger.info(s"Attempting: LDAP authentication of DN: ${dn}")
    try {
      val subject: Subject = SecurityUtils.getSubject
      subject.login(token)
    } catch {
      case _: Throwable => result = false
        if (url == "" || searchBase == "" || systemUsername == "" || systemPassword == "") {
          Logger.error("Error: LDAP configuration was not setup.")
        }
    }
    // If authentication fails, and if account is no longer in LDAP, then remove the shadowedAccount
    if (!result && lookupAccountByPrimary(account.username).isEmpty) {
      //
      // for each shadowed group for shadowed user, if not found in LDAP, then remove shadowed group
      for (g <- GroupFactory.listAccountGroups(account.id.asInstanceOf[UUID])) {
        if (this.ldapFindGroupnamesByName(g.name).get.isEmpty) {
          GroupFactory.delete(g.id.asInstanceOf[UUID])
        }
      }
      // find shadowed groups the shadowed user is in (Gestalt)
      UserAccountRepository.destroy(account)
    }
    result
  }

  // Returns the shadowed group
  override def lookupGroupByName(groupName: String): Option[UserGroupRepository] = UserGroupRepository.findBy(sqls"dir_id = ${id} and name = ${groupName}")

  override def lookupAccountByPrimary(primary: String): Option[UserAccountRepository] = {
    Logger.info(s"Attempting: LDAP lookupAccountByPrimary of ${primary}")
    try {
      val contextFactory = new JndiLdapContextFactory()
      contextFactory.setUrl(url)
      val sep = if (searchBase.startsWith(",")) "" else ","
      val dn = "cn=" + systemUsername + sep + searchBase
      val searchdn = s"${primaryField}=${primary}"
      contextFactory.setSystemUsername(systemUsername)
      contextFactory.setSystemPassword(systemPassword)
      val context = contextFactory.getLdapContext(dn.asInstanceOf[AnyRef], systemPassword.asInstanceOf[AnyRef])
      val constraints = new SearchControls()
      constraints.setSearchScope(SearchControls.SUBTREE_SCOPE)
      Logger.info(s"Attempting: LDAP lookupAccountByPrimary - search of DN: ${searchdn}")
      val answer: NamingEnumeration[SearchResult] = context.search(searchBase, searchdn, constraints)

      if (answer.hasMore) {
        val current = answer.next()
        val attrs = current.getAttributes
        val username = primary
        val firstname = if (attrs.get(firstnameField) != null) attrs.get(firstnameField).toString else ""
        val lastname = if (attrs.get(lastnameField) != null) attrs.get(lastnameField).toString else ""
        val description = if (attrs.get(descField) != null) Some(attrs.get(descField).toString) else None
        val email = if (attrs.get(emailField) != null) Some(attrs.get(emailField).toString) else None
        val phone = if (attrs.get(phoneField) != null) Some(attrs.get(phoneField).toString) else None
        Logger.info(s"Attempting: LDAP lookupAccountByPrimary - found: ${username}")
        // Create subject in shadow directory for auth
        for {
          account <- this.findAccountByUsername(username).orElse(this.shadowAccount(username, description, firstname, lastname, email, phone, GestaltPasswordCredential("Notused")).toOption)
        } yield account
      } else {
        None
      }
    } catch {
      case e: Throwable =>
        if (url == "" || searchBase == "" || systemUsername == "" || systemPassword == "") {
          Logger.error("Error: LDAP configuration was not setup.")
        }
        Logger.error("Error: Error accessing LDAP:  " + e.getMessage)
        None
    }
  }

  override def disableAccount(accountId: UUID): Unit = {
    AccountFactory.disableAccount(accountId)
  }

  override def lookupAccountByUsername(username: String): Option[UserAccountRepository] = {
    UserAccountRepository.findBy(sqls"dir_id=${id} and username=${username}")
  }

  // Syncs with LDAP before returning matching groups
  override def listOrgGroupsByName(orgId: UUID, groupName: String): Seq[UserGroupRepository] = {

    for (sgroup <- UserGroupRepository.findAllBy(sqls"dir_id = ${this.id}")) {
      if (this.ldapFindGroupnamesByName(groupName).get.isEmpty) {
        this.unshadowGroup(sgroup)
      }
    }
    // Find in LDAP
    for {
      ldapGroupname <- this.ldapFindGroupnamesByName(groupName).get
      shadowGroup <- UserGroupRepository.findAllBy(sqls"dir_id = ${id} and name = ${ldapGroupname}").headOption.orElse(this.shadowGroup(ldapGroupname, Some("LDAP Group")).toOption)
    } yield shadowGroup
  }

  override def getGroupById(groupId: UUID) = {
    GroupFactory.find(groupId) flatMap { grp =>
      if (grp.dirId == id) Some(grp)
      else None
    }
  }

  override def listGroupAccounts(groupId: UUID): Seq[UserAccountRepository] = groupFactory.listGroupAccounts(groupId)(session)

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

  private def shadowAccount(username: String, description: Option[String], firstName: String, lastName: String, email: Option[String], phoneNumber: Option[String], cred: GestaltPasswordCredential): Try[UserAccountRepository] = {
    val saccount = accountFactory.createAccount(
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
    )(session)
    val groups = for {
      gname <- this.ldapFindGroupnamesByUser(username).toOption.get.headOption
      group <- this.findGroupsByName(gname).headOption.orElse(shadowGroup(gname, Some("LDAP")).toOption)
//      group <- shadowGroup(gname, Some("LDAP")).toOption
    } yield group
    for (g <- groups) {
      groupFactory.addAccountToGroup(g.id.asInstanceOf[UUID], saccount.get.id.asInstanceOf[UUID])(session)
    }
    saccount
  }

  private def shadowGroup(groupName: String, description: Option[String]): Try[UserGroupRepository] = {
    groupFactory.create(groupName, description, id, orgId)(session)
  }

  private def unshadowGroup(group: UserGroupRepository): Try[Boolean] = {
    Try(groupFactory.delete(group.id.asInstanceOf[UUID])(session))
  }

  private def findAccountByUsername(username: String): Option[UserAccountRepository] = {
    UserAccountRepository.findBy(sqls"dir_id = ${this.id} and username = ${username}")
  }

  private def findGroupsByName(groupname: String): List[UserGroupRepository] = {
    UserGroupRepository.findAllBy(sqls"dir_id = ${this.id} and name = ${groupname}")
  }

  // Find an LDAP group by user
  private def ldapFindGroupnamesByUser(username: String): Try[List[String]] = {
    try {
      val contextFactory = new JndiLdapContextFactory()
      contextFactory.setUrl(url)
      val searchtail = if (searchBase.startsWith(",")) searchBase else s",${searchBase}"
      val dn = "cn=" + systemUsername + searchtail
      contextFactory.setSystemUsername(systemUsername)
      contextFactory.setSystemPassword(systemPassword)
      val context = contextFactory.getLdapContext(dn.asInstanceOf[AnyRef], systemPassword.asInstanceOf[AnyRef])
      val constraints = new SearchControls()
      constraints.setSearchScope(SearchControls.SUBTREE_SCOPE)
      val searchdn = s"(&(${groupField}=*)(${memberField}=${primaryField}=${username}${searchtail}))"
      val answer: NamingEnumeration[SearchResult] = context.search(searchBase, searchdn, constraints)
      var gnames = List.empty[String]
      while (answer.hasMore) {
        val current = answer.next()
        val attrs = current.getAttributes
        if (attrs.get(groupField) != null) {
          val raw = attrs.get(groupField).toString
          gnames = raw.split(" ").last :: gnames
        }
      }
      Success(gnames)
    } catch {
      case e: Throwable =>
        if (url == "" || searchBase == "" || systemUsername == "" || systemPassword == "") {
          Logger.error("Error: LDAP configuration was not setup.")
        }
        Logger.error("Error: Error accessing LDAP:  " + e.getMessage)
        Failure(e)
    }
  }

  def ldapFindGroupnamesByName(groupName: String): Try[List[String]] = {
    try {
      val contextFactory = new JndiLdapContextFactory()
      contextFactory.setUrl(url)
      val sep = if (searchBase.startsWith(",")) "" else ","
      val dn = "cn=" + systemUsername + sep + searchBase
      contextFactory.setSystemUsername(systemUsername)
      contextFactory.setSystemPassword(systemPassword)
      // LDAP search value
      val searchdn = s"${groupField}=${groupName}${sep}${searchBase}"
      val context = contextFactory.getLdapContext(dn.asInstanceOf[AnyRef], systemPassword.asInstanceOf[AnyRef])
      val constraints = new SearchControls()
      constraints.setSearchScope(SearchControls.SUBTREE_SCOPE)
      val answer: NamingEnumeration[SearchResult] = context.search(searchBase, searchdn, constraints)
      var groupNames = List.empty[String]
      while (answer.hasMore) {
        val current = answer.next()
        val attrs = current.getAttributes
        if (attrs.get(groupField) != null) {
          val raw = attrs.get(groupField).toString
          groupNames = raw.split(" ").last :: groupNames
        }
      }
      Success(groupNames)
    } catch {
      case e: Throwable =>
        if (url == "" || searchBase == "" || systemUsername == "" || systemPassword == "") {
          Logger.error("Error: LDAP configuration was not setup.")
        }
        Logger.error("Error: Error accessing LDAP:  " + e.getMessage)
        Failure(e)
    }
  }

}

//class GFILdapAdapter extends GestaltAdapter {
//
//}
//
//object GFILdapAdapter extends GestaltAdapter {
//
//	def init: LDAPAdapter = {
//		this.adapterName = "GFILDAPAdapter"
//		ServiceFactory.loadPlugin[GFILdapAdapter]("plugins", adpaterName, this.getClass.getClassLoader)
//	}
//
//	def getFramework : GFILdapFramework = new GFILdapFramework
//
//}
