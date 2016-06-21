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
  var userObjectClassDefault = if (activeDirectory == true) "user" else "person"
  var userObjectClass = (config \ "userObjectClass").asOpt[String].getOrElse(userObjectClassDefault)
  var memberField = (config \ "memberField").asOpt[String].getOrElse("uniqueMember")
  val ldapRealm = new ActiveDirectoryRealm()
  ldapRealm.setUrl(url)
  ldapRealm.setSearchBase(searchBase)
  ldapRealm.setSystemUsername(systemUsername)
  ldapRealm.setSystemPassword(systemPassword)
  val securityMgr = new DefaultSecurityManager(ldapRealm)
  SecurityUtils.setSecurityManager(securityMgr)

  def updateAccountMemberships(account: UserAccountRepository)
                              (implicit session: DBSession = AutoSession): Try[Seq[UserGroupRepository]] = {
    val shadowedGroups = GroupFactory.listAccountGroups(account.id.asInstanceOf[UUID])
    for {
      inGroupNames <- this.ldapFindGroupnamesByUser(account.username)
      inGroups <- Try {
        for {
          name <- inGroupNames
          group = this.findShadowedGroupByName(name).fold(
            shadowGroup(name, Some("LDAP shadowed group"))
          )(Success(_))
        } yield group.get
      }
      // all groups exist
      missingMembership = inGroups diff shadowedGroups
      staleMembership = shadowedGroups diff inGroups
      _ = staleMembership foreach { g =>
        groupFactory.removeAccountFromGroup(groupId = g.id.asInstanceOf[UUID], accountId = account.id.asInstanceOf[UUID])
      }
      _ = for {
          g <- missingMembership
          membership = groupFactory.addAccountToGroup(groupId = g.id.asInstanceOf[UUID], accountId = account.id.asInstanceOf[UUID])
      } yield membership
    } yield inGroups
  }

  override def authenticateAccount(account: UserAccountRepository, plaintext: String)
                                  (implicit session: DBSession): Boolean = {
    if (account.disabled) Logger.warn(s"LDAPDirectory.authenticateAccount called against disabled account ${account.id}")
    var result = true
    val sep = if (searchBase.startsWith(",")) "" else ","
    val dn = primaryField + "=" + account.username + sep + searchBase
    val token = new UsernamePasswordToken(dn, plaintext)
    Logger.info(s"Attempting: LDAP authentication of DN: ${dn}")
    try {
      val subject: Subject = SecurityUtils.getSubject
      subject.logout()
      val session = subject.getSession(false)
      if (session != null) {
        session.stop()
      }
      subject.login(token)
    } catch {
      case err: Throwable => result = false
        if (url == "" || searchBase == "" || systemUsername == "" || systemPassword == "") {
          Logger.error("Error: LDAP configuration was not setup.")
        } else {
          Logger.error("error authenticating with LDAP",err)
        }
    }
    // If authentication fails, and if account is no longer in LDAP, then remove the shadowedAccount
    // this is an opportunity for a short-circuit... if authentication succeeded, then necessarily the user is still in LDAP
    if (!result && lookupAccounts(username = Some(account.username)).isEmpty) {
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

  override def disableAccount(accountId: UUID, disabled: Boolean = true)(implicit session: DBSession): Unit = {
    AccountFactory.disableAccount(accountId, disabled)
  }

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
  override def lookupAccounts(group: Option[UserGroupRepository] = None,
                              username: Option[String] = None,
                              phone: Option[String] = None,
                              email: Option[String] = None)
                             (implicit session: DBSession = AutoSession): Seq[UserAccountRepository] = {
    if (username.isEmpty && phone.isEmpty && email.isEmpty) throw new RuntimeException("LDAPDirectory.lookupAccounts requires some search term")
    implicit def attrToString(attr: => Attribute): String = {
      val memo = attr
      if (memo != null && memo.size() > 0) memo.get(0).toString
      else ""
    }
    implicit def attrToOString(attr: => Attribute): Option[String] = {
      val memo = attr
      for {
        attr <- Option(memo)
        valueAny <- Option(attr.get(0))
        value = valueAny.toString
        if !value.trim.isEmpty
      } yield value
    }

    Logger.info(s"Attempting: LDAP lookupAccountByUsername of ${username}")
    val users = Try {
      val contextFactory = new JndiLdapContextFactory()
      contextFactory.setUrl(url)
      val sep = if (searchBase.startsWith(",")) "" else ","
      val dn = if (activeDirectory == true && systemUsername.indexOf("@") > 0) "cn=" + systemUsername.split("@").head + sep + searchBase else "cn=" + systemUsername + sep + searchBase

      val queries = Seq(
          username map (q => s"${primaryField}=$q"),
          email map (q => s"${emailField}=$q"),
          phone map (q => s"${phoneField}=$q")
      ).flatten
      val searchdn = s"(&(objectClass=${userObjectClass})(" + queries.foldLeft(""){ case (r,s) => r + "," + s }.stripPrefix(",") + "))"
      contextFactory.setSystemUsername(systemUsername)
      contextFactory.setSystemPassword(systemPassword)
      val context = contextFactory.getLdapContext(dn.asInstanceOf[AnyRef], systemPassword.asInstanceOf[AnyRef])
      val constraints = new SearchControls()
      constraints.setSearchScope(SearchControls.SUBTREE_SCOPE)
      Logger.info(s"Attempting: LDAP lookupAccountByUsername - search of DN: ${searchdn}")
      val answer: NamingEnumeration[SearchResult] = context.search(searchBase, searchdn, constraints)

      var users = Seq.empty[UserAccountRepository]
      while (answer.hasMore) {
        val current = answer.next()
        val attrs = current.getAttributes
        val uname: String         = attrs.get(primaryField)
        val fname: String         = attrs.get(firstnameField)
        val lname: String         = attrs.get(lastnameField)
        val desc: Option[String]  = attrs.get(descField)
        val email: Option[String] = attrs.get(emailField)
        val phone: Option[String] = attrs.get(phoneField)
        Logger.info(s"Attempting: LDAP findShadowAccountByUsername(${uname})")
        val newAccount = this.findShadowedAccountByUsername(uname) orElse {
          Logger.info(s"Attempting: LDAP shadowAccount(${uname})")
          this.shadowAccount(uname, desc, fname, lname, email, phone).toOption
        }
        newAccount match {
          case Some(account) =>
            Logger.info(s"Attempting: LDAP updateAccountMemberships(${account.username})")
            logError(this.updateAccountMemberships(account))
            users = users :+ account
          case None =>
            Logger.warn(s"did not find account ${uname}")
        }
      }
      group match {
        case None => users
        case Some(g) => users.filter(
          u => GroupMembershipRepository.find(accountId = u.id, g.id).isDefined
        )
      }
    }
    logError(users) get
  }

  /**
    * Directory-specific (i.e., deep) query of groups, supporting wildcard match on group name.
    *
    * Wildcard charater '*' matches any number of characters; multiple wildcards may be present at any location in the query string.
    *
    * @param groupName group name query parameter (e.g., "*-admins")
    * @param session   database session (optional)
    * @return List of matching groups
    */
  override def lookupGroups(groupName: String)(implicit session: DBSession): Seq[UserGroupRepository] = {
    // 1. get a list of matching groups in ldap
    // 2. get a list of matching shadowed groups
    // make #2 equal to #1
    val shadowedGroups = GroupFactory.queryShadowedDirectoryGroups(dirId = Some(id), nameQuery = Some(groupName))
    val ldapGroupNames = this.ldapFindGroupnamesByName(groupName).get
    val (staleGroups,notStaleGroups) = shadowedGroups.partition(
      g => ldapGroupNames.contains(g.name)
    )
    staleGroups.foreach(this.unshadowGroup)
    for {
      ldapGroupName <- ldapGroupNames
      shadowGroup <- notStaleGroups.find(_.name == ldapGroupName) orElse this.shadowGroup(ldapGroupName, Some("LDAP shadow group")).toOption
    } yield shadowGroup
  }

  override def getGroupById(groupId: UUID)(implicit session: DBSession): Option[UserGroupRepository] = {
    GroupFactory.find(groupId) flatMap { grp =>
      if (grp.dirId == id) Some(grp)
      else None
    }
  }

  override def listGroupAccounts(groupId: UUID)(implicit session: DBSession): Seq[UserAccountRepository] = groupFactory.listGroupAccounts(groupId)(session)

  override def deleteGroup(groupId: UUID)(implicit session: DBSession): Boolean = {
    GroupFactory.delete(groupId)
  }

  override def createGroup(name: String, description: Option[String])(implicit session: DBSession): Try[UserGroupRepository] = {
    Failure(BadRequestException(
      resource = "",
      message = "Group create request not valid",
      developerMessage = "LDAP Directory does not support group creation."
    ))
  }

  override def createAccount(username: String,
                             description: Option[String],
                             firstName: String,
                             lastName: String,
                             email: Option[String],
                             phoneNumber: Option[String],
                             cred: GestaltPasswordCredential)
                            (implicit session: DBSession): Try[UserAccountRepository] = {
    Failure(BadRequestException(
      resource = "",
      message = "Account create request not valid",
      developerMessage = "LDAP Directory does not support account creation."
    ))
  }

  private def shadowAccount(username: String, description: Option[String], firstName: String, lastName: String, email: Option[String], phoneNumber: Option[String])
                           (implicit session: DBSession): Try[UserAccountRepository] = DB localTx { implicit session =>
    accountFactory.createAccount(
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
  }

  // TODO (cgbaker): not using this yet, but there is a test protecting it.
  // I thought it might be useful for updating group membership on group lookup, but I haven't put it into play yet.
  def ldapFindUserDNsByGroup(groupName: String): Try[List[String]] = {
    val names = Try {
      val contextFactory = new JndiLdapContextFactory()
      contextFactory.setUrl(url)
      val sep = if (searchBase.startsWith(",")) "" else ","
      val dn = if (activeDirectory == true && systemUsername.indexOf("@") > 0) "cn=" + systemUsername.split("@").head + sep + searchBase else "cn=" + systemUsername + sep + searchBase
      contextFactory.setSystemUsername(systemUsername)
      contextFactory.setSystemPassword(systemPassword)
      // LDAP search value
      val searchdn = s"(&(${groupField}=${groupName})(objectClass=${groupObjectClass}))"
      val context = contextFactory.getLdapContext(dn.asInstanceOf[AnyRef], systemPassword.asInstanceOf[AnyRef])
      val constraints = new SearchControls()
      constraints.setSearchScope(SearchControls.SUBTREE_SCOPE)
      val answer: NamingEnumeration[SearchResult] = context.search(searchBase, searchdn, constraints)
      var userNames = List.empty[String]
      while (answer.hasMore) {
        val currentGroup = answer.next()
        val newMembers = for {
          memberAttr <- Option(currentGroup.getAttributes.get(memberField))
          members = (0 to memberAttr.size()-1).flatMap { i => Option(memberAttr.get(i).toString) }
        } yield members
        userNames = userNames ++ (newMembers getOrElse Seq.empty[String])
      }
      userNames.distinct
    }
    logError(names)
  }

  private def logError[T](t: Try[T]): Try[T] = {
    t match {
      case Success(s) => Success(s)
      case Failure(e) =>
        if (url == "" || searchBase == "" || systemUsername == "" || systemPassword == "") {
          Logger.error("Error: LDAP configuration was not setup.")
        }
        Logger.error("Error: Error accessing LDAP:  " + e.getMessage)
        Failure(e)
    }
  }

  private def shadowGroup(groupName: String, description: Option[String])(implicit session: DBSession): Try[UserGroupRepository] = {
    groupFactory.create(groupName, description, id, Some(orgId))
  }

  private def unshadowGroup(group: UserGroupRepository)(implicit session: DBSession): Try[Boolean] = {
    Try(groupFactory.delete(group.id.asInstanceOf[UUID]))
  }

  private def findShadowedAccountByUsername(username: String)(implicit session: DBSession): Option[UserAccountRepository] = {
    AccountFactory.findInDirectoryByName(this.id, username)
  }

  private def findShadowedGroupByName(groupname: String)(implicit session: DBSession): Option[UserGroupRepository] = {
    GroupFactory.findInDirectoryByName(this.id, groupname)
  }

  // Find an LDAP group by user
  def ldapFindGroupnamesByUser(username: String): Try[List[String]] = {
    logError(Try {
      val contextFactory = new JndiLdapContextFactory()
      contextFactory.setUrl(url)
      val searchtail = if (searchBase.startsWith(",")) searchBase else s",${searchBase}"
      val dn = if (activeDirectory == true && systemUsername.indexOf("@") > 0) "cn=" + systemUsername.split("@").head + searchtail else "cn=" + systemUsername + searchtail
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
    })
  }

  def ldapFindGroupnamesByName(groupName: String): Try[List[String]] = {
    val names = Try {
      val contextFactory = new JndiLdapContextFactory()
      contextFactory.setUrl(url)
      val sep = if (searchBase.startsWith(",")) "" else ","
      val dn = if (activeDirectory == true && systemUsername.indexOf("@") > 0) "cn=" + systemUsername.split("@").head + sep + searchBase else "cn=" + systemUsername + sep + searchBase
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
      groupNames
    }
    logError(names)
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
