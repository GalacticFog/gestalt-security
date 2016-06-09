package com.galacticfog.gestalt.security.data.domain

import java.util.UUID
import javax.naming.NamingEnumeration
import javax.naming.directory.{Attribute, SearchControls, SearchResult}

import com.galacticfog.gestalt.security.api.GestaltPasswordCredential
import com.galacticfog.gestalt.security.api.errors.BadRequestException
import com.galacticfog.gestalt.security.data.model._
import org.apache.shiro.SecurityUtils
import org.apache.shiro.authc.UsernamePasswordToken
import org.apache.shiro.mgt.DefaultSecurityManager
import org.apache.shiro.realm.activedirectory.ActiveDirectoryRealm
import org.apache.shiro.realm.ldap.JndiLdapContextFactory
import org.apache.shiro.subject.Subject
import play.api.Logger
import play.api.libs.json.Json
import scalikejdbc._
import scalikejdbc.TxBoundary.Try._

import scala.util.{Failure, Success, Try}

case class LDAPDirectory(daoDir: GestaltDirectoryRepository, accountFactory: AccountFactoryDelegate, groupFactory: GroupFactoryDelegate) extends Directory {
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
  var descField = (config \ "descriptionField").asOpt[String].getOrElse("description")
  var firstnameField = (config \ "firstNameField").asOpt[String].getOrElse("firstName")
  var lastnameField = (config \ "lastNameField").asOpt[String].getOrElse("lastName")
  var emailField = (config \ "emailField").asOpt[String].getOrElse("mail")
  var phoneField = (config \ "phoneNumberField").asOpt[String].getOrElse("phoneNumber")
  var groupField = (config \ "groupField").asOpt[String].getOrElse("cn")
  var groupObjectClassDefault = if (activeDirectory == true) "group" else "groupOfUniqueNames"
  var groupObjectClass = (config \ "groupObjectClass").asOpt[String].getOrElse(groupObjectClassDefault)
  var memberField = (config \ "memberField").asOpt[String].getOrElse("uniqueMember")
  val ldapRealm = new ActiveDirectoryRealm()
  ldapRealm.setUrl(url)
  ldapRealm.setSearchBase(searchBase)
  ldapRealm.setSystemUsername(systemUsername)
  ldapRealm.setSystemPassword(systemPassword)
  val securityMgr = new DefaultSecurityManager(ldapRealm)
  SecurityUtils.setSecurityManager(securityMgr)

  override def authenticateAccount(account: UserAccountRepository, plaintext: String)(implicit session: DBSession): Boolean = {
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
    // this is an opportunity for a short-circuit... if authentication succeeded, then necessarily the user is still in LDAP
    if (!result && lookupAccountByUsername(account.username).isEmpty) {
      //
      // for each shadowed group for shadowed user, if not found in LDAP, then remove shadowed group
      GroupFactory.listAccountGroups(account.id.asInstanceOf[UUID]) filter {
        g => this.ldapFindGroupnamesByName(g.name).toOption.exists(_.isEmpty)
      } foreach {
        _.destroy()
      }
      account.destroy()
    }
    result
  }

  // Returns the shadowed group
  override def lookupGroupByName(groupName: String)(implicit session: DBSession): Option[UserGroupRepository] = UserGroupRepository.findBy(sqls"dir_id = ${id} and name = ${groupName}")

  override def disableAccount(accountId: UUID)(implicit session: DBSession): Unit = {
    AccountFactory.disableAccount(accountId)
  }

  override def lookupAccountByUsername(username: String)(implicit session: DBSession): Option[UserAccountRepository] = {
    implicit def attrToString(attr: => Attribute): String = {
      val memo = attr
      if (memo != null) memo.toString
      else ""
    }
    implicit def attrToOString(attr: => Attribute): Option[String] = {
      val memo = attr
      Option[String](memo) map {_.toString} flatMap {
        s => if (s.trim.isEmpty) None else Some(s.trim)
      }
    }

    Logger.info(s"Attempting: LDAP lookupAccountByUsername of ${username}")
    try {
      val contextFactory = new JndiLdapContextFactory()
      contextFactory.setUrl(url)
      val sep = if (searchBase.startsWith(",")) "" else ","
      val dn = "cn=" + systemUsername + sep + searchBase
      val searchdn = s"${primaryField}=${username}"
      contextFactory.setSystemUsername(systemUsername)
      contextFactory.setSystemPassword(systemPassword)
      val context = contextFactory.getLdapContext(dn.asInstanceOf[AnyRef], systemPassword.asInstanceOf[AnyRef])
      val constraints = new SearchControls()
      constraints.setSearchScope(SearchControls.SUBTREE_SCOPE)
      Logger.info(s"Attempting: LDAP lookupAccountByUsername - search of DN: ${searchdn}")
      val answer: NamingEnumeration[SearchResult] = context.search(searchBase, searchdn, constraints)

      if (answer.hasMore) {
        val current = answer.next()
        val attrs = current.getAttributes
        val fname: String         = attrs.get(firstnameField)
        val lname: String         = attrs.get(lastnameField)
        val desc: Option[String]  = attrs.get(descField)
        val email: Option[String] = attrs.get(emailField)
        val phone: Option[String] = attrs.get(phoneField)
        Logger.info(s"Attempting: LDAP findShadowAccountByUsername(${username})")
        this.findShadowedAccountByUsername(username) orElse {
          Logger.info(s"Attempting: LDAP shadowAccount(${username})")
          this.shadowAccount(username, desc, fname, lname, email, phone).toOption
        }
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

  // Syncs with LDAP before returning matching groups
  override def listOrgGroupsByName(orgId: UUID, groupName: String)(implicit session: DBSession): Seq[UserGroupRepository] = {
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

  override def getGroupById(groupId: UUID)(implicit session: DBSession) = {
    GroupFactory.find(groupId) flatMap { grp =>
      if (grp.dirId == id) Some(grp)
      else None
    }
  }

  override def listGroupAccounts(groupId: UUID)(implicit session: DBSession): Seq[UserAccountRepository] = groupFactory.listGroupAccounts(groupId)(session)

  override def deleteGroup(groupId: UUID)(implicit session: DBSession): Boolean = {
    GroupFactory.delete(groupId)
  }

  override def createAccount(username: String, description: Option[String], firstName: String, lastName: String, email: Option[String], phoneNumber: Option[String], cred: GestaltPasswordCredential)(implicit session: DBSession): Try[UserAccountRepository] = {
    Failure(BadRequestException(
      resource = s"/orgs/${orgId}/directories/${id}/account",
      message = "Account create request not valid",
      developerMessage = "LDAP Directory does not support account creation."
    ))
  }

  private def shadowAccount(username: String, description: Option[String], firstName: String, lastName: String, email: Option[String], phoneNumber: Option[String])
                           (implicit session: DBSession): Try[UserAccountRepository] = DB localTx { implicit session =>
    for {
      saccount <- accountFactory.createAccount(
        dirId = id,
        username = username,
        description = description,
        firstName = firstName,
        lastName = lastName,
        email = email,
        phoneNumber = phoneNumber,
        hashMethod = "shadowed",
        salt = "",
        secret = "",
        disabled = false
      )
      groupNames <- this.ldapFindGroupnamesByUser(username)
      groups <- Try {
        for {
          gname <- groupNames
          group = this.findShadowedGroupByName(gname).fold(
            shadowGroup(gname, Some("LDAP"))
          )(Success(_))
        } yield group.get
      }
      groupMemberships <- Try {
        groups map { g =>
          groupFactory.addAccountToGroup(g.id.asInstanceOf[UUID], saccount.id.asInstanceOf[UUID])
        } map {_.get}
      }
    } yield saccount
  }

  private def shadowGroup(groupName: String, description: Option[String])(implicit session: DBSession): Try[UserGroupRepository] = {
    groupFactory.create(groupName, description, id, Some(orgId))
  }

  private def unshadowGroup(group: UserGroupRepository)(implicit session: DBSession): Try[Boolean] = {
    Try(groupFactory.delete(group.id.asInstanceOf[UUID]))
  }

  private def findShadowedAccountByUsername(username: String)(implicit session: DBSession): Option[UserAccountRepository] = {
    UserAccountRepository.findBy(sqls"dir_id = ${this.id} and username = ${username}")
  }

  private def findShadowedGroupByName(groupname: String)(implicit session: DBSession): Option[UserGroupRepository] = {
    UserGroupRepository.findBy(sqls"dir_id = ${this.id} and name = ${groupname}")
  }

  // Find an LDAP group by user
  private def ldapFindGroupnamesByUser(username: String): Try[List[String]] = {
    Try {
      val contextFactory = new JndiLdapContextFactory()
      contextFactory.setUrl(url)
      val searchtail = if (searchBase.startsWith(",")) searchBase else s",${searchBase}"
      val dn = "cn=" + systemUsername + searchtail
      contextFactory.setSystemUsername(systemUsername)
      contextFactory.setSystemPassword(systemPassword)
      val context = contextFactory.getLdapContext(dn.asInstanceOf[AnyRef], systemPassword.asInstanceOf[AnyRef])
      val constraints = new SearchControls()
      constraints.setSearchScope(SearchControls.SUBTREE_SCOPE)
      val searchdn = s"(&(${groupField}=*)(${memberField}=${primaryField}=${username}${searchtail})(objectClass=${groupObjectClass}))"
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
      gnames
    } recoverWith {
      case e: Throwable =>
        if (url == "" || searchBase == "" || systemUsername == "" || systemPassword == "") {
          Logger.error("Error: LDAP configuration was not setup.")
        }
        Logger.error("Error: Error accessing LDAP:  " + e.getMessage)
        Failure(e)
    }
  }

  private def ldapFindGroupnamesByName(groupName: String): Try[List[String]] = {
    try {
      val contextFactory = new JndiLdapContextFactory()
      contextFactory.setUrl(url)
      val sep = if (searchBase.startsWith(",")) "" else ","
      val dn = "cn=" + systemUsername + sep + searchBase
      contextFactory.setSystemUsername(systemUsername)
      contextFactory.setSystemPassword(systemPassword)
      // LDAP search value
      val searchdn = s"(&(${groupField}=${groupName})(objectClass=${groupObjectClass}))"
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
