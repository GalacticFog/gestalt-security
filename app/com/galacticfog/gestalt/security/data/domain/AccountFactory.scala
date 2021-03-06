package com.galacticfog.gestalt.security.data.domain

import java.util.UUID

import com.galacticfog.gestalt.patch.PatchOp
import com.galacticfog.gestalt.security.api._
import com.galacticfog.gestalt.security.api.errors.{BadRequestException, ConflictException, ResourceNotFoundException, UnknownAPIException}
import com.galacticfog.gestalt.security.data.APIConversions
import com.galacticfog.gestalt.security.data.model._
import com.galacticfog.gestalt.security.plugins.DirectoryPlugin
import com.galacticfog.gestalt.security.adapter.LDAPDirectory
import org.mindrot.jbcrypt.BCrypt
import org.postgresql.util.PSQLException
import play.api.Logger
import scalikejdbc._

import scala.util.{Failure, Success, Try}
import scala.util.matching.Regex


object AccountFactory extends SQLSyntaxSupport[UserAccountRepository] {


  val E164_PHONE_NUMBER: Regex = """^\+\d{10,15}$""".r

  def instance: AccountFactory.type = this

  def canonicalE164(phoneNumber: String): String = {
    phoneNumber.replaceAll("[- ().]","")
  }

  override val autoSession = AutoSession

  def disableAccount(accountId: UUID, disabled: Boolean = true)(implicit session: DBSession = autoSession): Unit = {
    val column = UserAccountRepository.column
    withSQL {
      update(UserAccountRepository).set(column.disabled -> disabled).where.eq(column.id, accountId)
    }.update().apply()
  }

  def find(accountId: UUID)(implicit session: DBSession = autoSession): Option[UserAccountRepository] = {
    UserAccountRepository.find(accountId)
  }

  def findEnabled(accountId: UUID)(implicit session: DBSession = autoSession): Option[UserAccountRepository] = {
    UserAccountRepository.findBy(sqls"id = ${accountId} and disabled = false")
  }

  def listByDirectoryId(dirId: UUID)(implicit session: DBSession = autoSession): List[UserAccountRepository] = {
    UserAccountRepository.findAllBy(sqls"dir_id=${dirId}")
  }

  def checkPassword(account: UserAccountRepository, plaintext: String): Boolean = {
    account.hashMethod match {
      case "bcrypt" => BCrypt.checkpw(plaintext, account.secret)
      case "shadowed" =>
        Logger.warn("Cannot perform direct authentication against a shadow account")
        false
      case "disabled" =>
        Logger.info("Account password authenticated marked disabled")
        false
      case "" => account.secret.equals(plaintext)
      case s: String =>
        Logger.warn("Unsupported password hash method: " + s)
        false
    }
  }

  def createAccount(dirId: UUID,
                    username: String,
                    description: Option[String] = None,
                    email: Option[String] = None,
                    phoneNumber: Option[String] = None,
                    firstName: String,
                    lastName: String,
                    hashMethod: String,
                    salt: String,
                    secret: String,
                    disabled: Boolean)
                   (implicit session: DBSession = autoSession): Try[UserAccountRepository] =
  {
    Try {
      UserAccountRepository.create(
        id = UUID.randomUUID(),
        dirId = dirId,
        username = username,
        email = email,
        phoneNumber = phoneNumber map canonicalE164,
        firstName = firstName,
        lastName = lastName,
        hashMethod = hashMethod,
        salt = salt,
        secret = secret,
        disabled = disabled,
        description = description
      )
    } recoverWith {
      case t: PSQLException if t.getSQLState == "23505" || t.getSQLState == "23514" =>
        t.getServerErrorMessage.getConstraint match {
          case "account_phone_number_check" => Failure(BadRequestException(
            resource = "",
            message = "phone number was not properly formatted",
            developerMessage = "The provided phone number must be formatted according to E.164."
          ))
          case "account_dir_id_username_key" => Failure(ConflictException(
            resource = "",
            message = "username already exists in directory",
            developerMessage = "An account with the specified username already exists in the specified directory."
          ))
          case "account_dir_id_email_key" => Failure(ConflictException(
            resource = "",
            message = "email address already exists in directory",
            developerMessage = "An account with the specified email address already exists in the specified directory."
          ))
          case "account_dir_id_phone_number_key" => Failure(ConflictException(
            resource = "",
            message = "phone number already exists in directory",
            developerMessage = "An account with the specified phone number already exists in the specified directory."
          ))
          case _ => Failure(t)
        }
    }
  }

  def saveAccount(account: UserAccountRepository)
                   (implicit session: DBSession = autoSession): Try[UserAccountRepository] = {
    Try {
      account.save()
    } recoverWith {
      case t: PSQLException if t.getSQLState == "23505" || t.getSQLState == "23514" =>
        t.getServerErrorMessage.getConstraint match {
          case "account_phone_number_check" => Failure(BadRequestException(
            resource = "",
            message = "phone number was not properly formatted",
            developerMessage = "The provided phone number must be formatted according to E.164."
          ))
          case "account_dir_id_username_key" => Failure(ConflictException(
            resource = "",
            message = "username already exists in directory",
            developerMessage = "An account with the specified username already exists in the specified directory."
          ))
          case "account_dir_id_email_key" => Failure(ConflictException(
            resource = "",
            message = "email address already exists in directory",
            developerMessage = "An account with the specified email address already exists in the specified directory."
          ))
          case "account_dir_id_phone_number_key" => Failure(ConflictException(
            resource = "",
            message = "phone number already exists in directory",
            developerMessage = "An account with the specified phone number already exists in the specified directory."
          ))
          case _ =>
            Logger.error("PSQLException in saveAccount",t)
            Failure(UnknownAPIException(
              code = 500,
              resource = "",
              message = "sql error",
              developerMessage = "SQL error updating account. Check the error log for more details."
            ))
        }
    }
  }

  def updateAccount(account: UserAccountRepository, patches: Seq[PatchOp])
                   (implicit session: DBSession = autoSession): Try[UserAccountRepository] = {
    val patchedAccount = patches.foldLeft(account)((acc, patch) => {
      patch.copy(op = patch.op.toLowerCase) match {
        case PatchOp("replace", "/username",Some(value))     => acc.copy(username = value.as[String])
        case PatchOp("replace", "/firstName",Some(value))    => acc.copy(firstName = value.as[String])
        case PatchOp("replace", "/lastName",Some(value))     => acc.copy(lastName = value.as[String])
        case PatchOp("replace", "/disabled", Some(value))    => acc.copy(disabled = value.as[Boolean])
        case PatchOp("replace", "/password", Some(value))    => {
          import com.galacticfog.gestalt.security.api.json.JsonImports._
          val newpass = BCrypt.hashpw(value.as[GestaltAccountCredential].asInstanceOf[GestaltPasswordCredential].password, BCrypt.gensalt())
          acc.copy(hashMethod = "bcrypt", secret = newpass)
        }
        case PatchOp("remove",  "/email", None)              => acc.copy(email = None)
        case PatchOp("add",     "/email", Some(value))       => acc.copy(email = Some(value.as[String]))
        case PatchOp("replace", "/email", Some(value))       => acc.copy(email = Some(value.as[String]))
        case PatchOp("remove",  "/phoneNumber", None)        => acc.copy(phoneNumber = None)
        case PatchOp("add",     "/phoneNumber", Some(value)) => acc.copy(phoneNumber = Some(value.as[String]))
        case PatchOp("replace", "/phoneNumber", Some(value)) => acc.copy(phoneNumber = Some(value.as[String]))
        case _ => throw BadRequestException(
          resource = "",
          message = "bad PATCH payload for updating account",
          developerMessage = "The PATCH payload for updating the account had invalid fields."
        )
      }
    })
    DirectoryFactory.find(account.dirId.asInstanceOf[UUID]) match {
      case Some(dir: InternalDirectory) => AccountFactory.saveAccount(patchedAccount.copy(phoneNumber = patchedAccount.phoneNumber map canonicalE164))
      case Some(dir: LDAPDirectory) =>
        Failure(ConflictException(resource = "",
                                  message = "LDAP Directory does not support updating accounts.",
                                  developerMessage = "LDAP Directory does not support updating accounts."))
      case Some(_) =>
        Failure(BadRequestException(resource = "",
          message = "Unknown directory type.",
          developerMessage = "Directory type is unknown. Please contact support."))
      case None => throw BadRequestException(
        resource = "",
        message = "A valid directory was not found for the account",
        developerMessage = "A valid directory was not found for the account.")
    }
  }

  def updateAccountSDK(account: UserAccountRepository, update: GestaltAccountUpdate)(implicit session: DBSession = autoSession): Try[UserAccountRepository] = {
    val cred = update.credential map {_.asInstanceOf[GestaltPasswordCredential].password}
    val newpass = cred map {p => BCrypt.hashpw(p, BCrypt.gensalt())}
    val newPhoneNumber = update.phoneNumber map canonicalE164
    val updatedAccount = account.copy(
      username = update.username getOrElse account.username,
      firstName = update.firstName getOrElse account.firstName,
      lastName = update.lastName getOrElse account.lastName,
      email = update.email match {
        case Some(empty) if empty.trim.isEmpty => None          // clear it
        case Some(newVal)                      => Some(newVal)  // replace it
        case None                              => account.email // keep it
      },
      phoneNumber = newPhoneNumber match {
        case Some(empty) if empty.trim.isEmpty => None         // clear it
        case Some(newVal)                      => Some(newVal) // replace it
        case None                              => account.phoneNumber
      },
      hashMethod = if (newpass.isDefined) "bcrypt" else account.hashMethod,
      secret = newpass getOrElse account.secret
    )
    DirectoryFactory.find(account.dirId.asInstanceOf[UUID]) match {
      case Some(dir: InternalDirectory) => AccountFactory.saveAccount(updatedAccount.copy(phoneNumber = updatedAccount.phoneNumber map canonicalE164))
      case Some(dir: LDAPDirectory) =>
        Failure(ConflictException(resource = "",
                                  message = "LDAP Directory does not support updating accounts.",
                                  developerMessage = "LDAP Directory does not support updating accounts."))
      case Some(_) =>
        Failure(BadRequestException(resource = "",
          message = "Unknown directory type.",
          developerMessage = "Directory type is unknown. Please contact support."))
      case None => throw BadRequestException(
        resource = "",
        message = "A valid directory was not found for the account",
        developerMessage = "A valid directory was not found for the account.")
    }
  }


  /**
    * Deep lookup on Directories to find all accounts that are mapped to a given application
    * @param appId ID for the application
    * @param nameQuery  username query parameter (e.g., "*smith")
    * @param emailQuery email query parameter (e.g., "*@company.com")
    * @param phoneQuery phone number query parameter (e.g., "+1505*")
    * @param session database session (optional)
    * @return List of accounts mapped to the application satisfying any specified query parameters
    */
  def lookupByAppId(appId: UUID, nameQuery: Option[String], emailQuery: Option[String], phoneQuery: Option[String])
                   (implicit session: DBSession = autoSession): Seq[GestaltAccount] = {
    val (dirMappings,groupMappings) = AppFactory.listAccountStoreMappings(appId) partition( _.storeType.toUpperCase == "DIRECTORY" )
    val dirAccounts = for {
      dir <- dirMappings.flatMap {dirMapping => DirectoryFactory.find(dirMapping.accountStoreId.asInstanceOf[UUID]).toSeq}
      acc <- dir.lookupAccounts(
        group = None,
        username = nameQuery,
        phone = phoneQuery,
        email = emailQuery
      )
    } yield acc
    lazy val groupAccounts = for {
      grpMapping <- groupMappings
      group <- UserGroupRepository.find(grpMapping.accountStoreId).toSeq
      dir <- DirectoryFactory.find(group.dirId.asInstanceOf[UUID]).toSeq
      accs <- dir.lookupAccounts(
        group = Some(APIConversions.groupModelToApi(group)),
        username = nameQuery,
        phone = phoneQuery,
        email = emailQuery
      )
    } yield accs
    (dirAccounts ++ groupAccounts).distinct
  }

  /**
    * For all account stores mapped to a particular application, find the "first" account in those stores against which
    * the given credentials can successfully authenticate.
    *
    * This uses the methods on the Directory interface for the relevant account stores, and therefore may result in calls to the
    * backing store (e.g., LDAP/AD) for the directory and potentially result in newly created shadow accounts.
    *
    * @param appId The app context for authentication
    * @param creds The user credentials to authenticate
    * @param session db session
    * @return Some account mapped to the specified application for which the credentials are valid, or None if there is no such account
    */
  def authenticate(appId: UUID, creds: GestaltBasicCredsToken)(implicit session: DBSession = autoSession): Option[GestaltAccount] = {
    def safeSeq[A](seq: => Seq[A]): Seq[A] = {
      Try{seq} match {
        case Success(s) => s
        case Failure(e) =>
          Logger.error("error looking up accounts in AccountFactory.authenticate",e)
          Seq.empty[A]
      }
    }
    def safeAuth(dir: DirectoryPlugin, acc: UserAccountRepository, plaintext: String): Boolean = {
      if (acc.disabled) {
        Logger.warn(s"LDAPDirectory.authenticateAccount called against disabled account ${acc.id}")
        return false
      }
      Try{dir.authenticateAccount(APIConversions.accountModelToApi(acc), plaintext)} match {
        case Success(b) => b
        case Failure(e) =>
          Logger.error(s"error authenticating account ${acc.username} in directory ${dir.id}",e)
          false
      }
    }

    if (creds.username.contains("*")) throw BadRequestException("", "username cannot contain wildcard characters", "There was an attempt to authenticate an account with a username containing wildcard characters. This is not allowed.")
    val (dirMappings,groupMappings) = AppFactory.listAccountStoreMappings(appId) partition( _.storeType.toUpperCase == "DIRECTORY" )
    val dirAccounts = for {
      dir <- dirMappings.flatMap {dirMapping => DirectoryFactory.find(dirMapping.accountStoreId.asInstanceOf[UUID]).toSeq}
      acc <- safeSeq(dir.lookupAccounts(
        group = None,
        username = Some(creds.username),
        phone = None,
        email = None
      ))
      dao <- UserAccountRepository.find(acc.id.asInstanceOf[UUID])
      authedAcc <- if (!dao.disabled && safeAuth(dir,dao,creds.password)) Some(acc) else None
    } yield authedAcc
    lazy val groupAccounts = for {
      grpMapping <- groupMappings
      group <- UserGroupRepository.find(grpMapping.accountStoreId).toSeq.map { APIConversions.groupModelToApi }
      dir <- DirectoryFactory.find(group.directory.id.asInstanceOf[UUID]).toSeq
      acc <- safeSeq(dir.lookupAccounts(
        group = Some(group),
        username = Some(creds.username),
        phone = None,
        email = None
      ))
      dao <- UserAccountRepository.find(acc.id.asInstanceOf[UUID])
      authedAcc <- if (!dao.disabled && safeAuth(dir,dao,creds.password)) Some(acc) else None
    } yield authedAcc
    val result = dirAccounts.headOption orElse groupAccounts.headOption
    result
  }

  def getAppAccount(appId: UUID, accountId: UUID)(implicit session: DBSession = autoSession): Option[UserAccountRepository] = {
    val a = UserAccountRepository.syntax("a")
    sql"""select distinct ${a.result.*}
          from ${UserAccountRepository.as(a)}
          inner join (
            select axg.account_id,asm.account_store_id from account_x_group as axg
              right join account_store_mapping as asm on asm.account_store_id = axg.group_id and asm.store_type = 'GROUP'
              where asm.app_id = ${appId}
          ) as sub on ${a.id} = sub.account_id or ${a.dirId} = sub.account_store_id
          where ${a.disabled} = false and ${a.id} = ${accountId}
      """.map{UserAccountRepository(a)}.list.apply().headOption
  }

  def listEnabledAppUsers(appId: UUID)(implicit session: DBSession = autoSession): List[UserAccountRepository] = {
    val (a, axg, asm) = (
      UserAccountRepository.syntax("a"),
      GroupMembershipRepository.syntax("axg"),
      AccountStoreMappingRepository.syntax("asm")
      )
    sql"""select distinct ${a.result.*}
          from ${UserAccountRepository.as(a)} inner join (
            select axg.account_id,asm.account_store_id,asm.store_type
            from account_x_group as axg
              right join account_store_mapping as asm on asm.account_store_id = axg.group_id and asm.store_type = 'GROUP'
              where asm.app_id = ${appId}
          ) as sub on (sub.store_type = 'GROUP' and ${a.id} = sub.account_id) or (sub.store_type = 'DIRECTORY' and ${a.dirId} = sub.account_store_id)
          where ${a.disabled} = false
      """.map(UserAccountRepository(a)).list.apply()
  }

  def queryShadowedDirectoryAccounts(dirId: Option[UUID], nameQuery: Option[String], emailQuery: Option[String], phoneQuery: Option[String])
                                    (implicit session: DBSession = autoSession): List[UserAccountRepository] = {
    val a = UserAccountRepository.syntax("a")
    withSQL {
      select
        .from(UserAccountRepository as a)
        .where(sqls.toAndConditionOpt(
          dirId.map(id => sqls.eq(a.dirId, id)),
          nameQuery.map(q => sqls.like(a.username, q.replace("*","%"))),
          emailQuery.map(q => sqls.like(a.email, q.replace("*","%"))),
          phoneQuery.map(q => sqls.like(a.phoneNumber, q.replace("*","%")))
        ))
    }.map{UserAccountRepository(a)}.list.apply()
  }

  def findInDirectoryByName(dirId: UUID, username: String)(implicit session: DBSession = autoSession): Option[UserAccountRepository] = {
    UserAccountRepository.findBy(sqls"dir_id=${dirId} and username=${username}")
  }

  def listAppAccountGrants(appId: UUID, accountId: UUID)(implicit session: DBSession = autoSession): Seq[RightGrantRepository] = {
    findAppUserByAccountId(appId, accountId) match {
      case Some(account) => RightGrantFactory.listAccountRights(appId, account.id.asInstanceOf[UUID])
      case None => throw ResourceNotFoundException(
        resource = "account",
        message = "could not locate application account",
        developerMessage = "Could not location application account while looking up application grants. Ensure that the specified account ID corresponds to an account that is assigned to the specified application."
      )
    }
  }

  def listAppGroupGrants(appId: UUID, groupId: UUID)(implicit session: DBSession = autoSession): Seq[RightGrantRepository] = {
    RightGrantFactory.listGroupRights(appId, groupId)
  }

  def getAppAccountGrant(appId: UUID, accountId: UUID, grantName: String)(implicit session: DBSession = autoSession): Option[RightGrantRepository] = listAppAccountGrants(appId, accountId) find (_.grantName == grantName)

  def getAppGroupGrant(appId: UUID, groupId: UUID, grantName: String)(implicit session: DBSession = autoSession): Option[RightGrantRepository] = listAppGroupGrants(appId, groupId) find (_.grantName == grantName)

  def deleteAppAccountGrant(appId: UUID, accountId: UUID, grantName: String)(implicit session: DBSession = autoSession): Boolean = {
    listAppAccountGrants(appId, accountId) find(_.grantName == grantName) match {
      case None => false
      case Some(grant) =>
        grant.destroy()
        true
    }
  }

  def deleteAppGroupGrant(appId: UUID, groupId: UUID, grantName: String)(implicit session: DBSession = autoSession): Boolean = {
    listAppGroupGrants(appId, groupId) find(_.grantName == grantName) match {
      case None => false
      case Some(grant) =>
        grant.destroy()
        true
    }
  }

  def updateAppAccountGrant(appId: UUID, accountId: UUID, grantName: String, patch: Seq[PatchOp])(implicit session: DBSession = autoSession): RightGrantRepository = {
    getAppAccountGrant(appId,accountId,grantName) match {
      case Some(grant) =>
        val newGrant = patch.foldLeft(grant)((g, p) => {
          p match {
            case PatchOp(op,"/grantValue",Some(value)) if op.toLowerCase == "add" || op.toLowerCase == "replace" =>
              g.copy(grantValue = Some(value.as[String]))
            case PatchOp("remove","/grantValue",None) =>
              g.copy(grantValue = None)
            case _ => throw BadRequestException(
              resource = "",
              message = "bad PATCH payload for updating account grant",
              developerMessage = "The PATCH payload for updating the accoutn grant had invalid fields."
            )
          }
        })
        newGrant.save()
      case None => throw ResourceNotFoundException(
        resource = "",
        message = "grant does not exist",
        developerMessage = "A grant with the given name does not exist for the given account in the given app."
      )
    }
  }

  def updateAppGroupGrant(appId: UUID, groupId: UUID, grantName: String, patch: Seq[PatchOp])(implicit session: DBSession = autoSession): RightGrantRepository = {
    getAppGroupGrant(appId,groupId,grantName) match {
      case Some(grant) =>
        val newGrant = patch.foldLeft(grant)((g, p) => {
          p match {
            case PatchOp(op,"/grantValue",Some(value)) if op.toLowerCase == "add" || op.toLowerCase == "replace" =>
              g.copy(grantValue = Some(value.as[String]))
            case PatchOp("remove","/grantValue",None) =>
              g.copy(grantValue = None)
            case _ => throw BadRequestException(
              resource = "",
              message = "bad PATCH payload for updating account grant",
              developerMessage = "The PATCH payload for updating the accoutn grant had invalid fields."
            )
          }
        })
        newGrant.save()
      case None => throw ResourceNotFoundException(
        resource = "",
        message = "grant does not exist",
        developerMessage = "A grant with the given name does not exist for the given account in the given app."
      )
    }
  }

  private[this] def findAppUserByAccountId(appId: UUID, accountId: UUID)(implicit session: DBSession = autoSession): Option[UserAccountRepository] = {
    val (a, axg, asm) = (
      UserAccountRepository.syntax("a"),
      GroupMembershipRepository.syntax("axg"),
      AccountStoreMappingRepository.syntax("asm")
      )
    sql"""select distinct ${a.result.*}
          from ${UserAccountRepository.as(a)} inner join (
            select axg.account_id,asm.account_store_id,asm.store_type
            from account_x_group as axg
              right join account_store_mapping as asm on asm.account_store_id = axg.group_id and asm.store_type = 'GROUP'
              where asm.app_id = ${appId}
          ) as sub on (sub.store_type = 'GROUP' and ${a.id} = sub.account_id) or (sub.store_type = 'DIRECTORY' and ${a.dirId} = sub.account_store_id)
          where ${a.id} = ${accountId}
      """.map(UserAccountRepository(a)).single().apply()
  }

  private[this] def findAppUsersByEmail(appId: UUID, email: String)(implicit session: DBSession = autoSession): List[UserAccountRepository] = {
    val (a, axg, asm) = (
      UserAccountRepository.syntax("a"),
      GroupMembershipRepository.syntax("axg"),
      AccountStoreMappingRepository.syntax("asm")
      )
    sql"""select distinct ${a.result.*}
          from ${UserAccountRepository.as(a)} inner join (
            select axg.account_id,asm.account_store_id,asm.store_type
            from account_x_group as axg
              right join account_store_mapping as asm on asm.account_store_id = axg.group_id and asm.store_type = 'GROUP'
              where asm.app_id = ${appId}
          ) as sub on (sub.store_type = 'GROUP' and ${a.id} = sub.account_id) or (sub.store_type = 'DIRECTORY' and ${a.dirId} = sub.account_store_id)
          where ${a.email} = ${email}
      """.map(UserAccountRepository(a)).list.apply()
  }

  private[this] def findAppUsersByUsername(appId: UUID, username: String)(implicit session: DBSession = autoSession): List[UserAccountRepository] = {
    val (a, axg, asm) = (
      UserAccountRepository.syntax("a"),
      GroupMembershipRepository.syntax("axg"),
      AccountStoreMappingRepository.syntax("asm")
      )
    sql"""select distinct ${a.result.*}
          from ${UserAccountRepository.as(a)} inner join (
            select axg.account_id,asm.account_store_id,asm.store_type
            from account_x_group as axg
              right join account_store_mapping as asm on asm.account_store_id = axg.group_id and asm.store_type = 'GROUP'
              where asm.app_id = ${appId}
          ) as sub on (sub.store_type = 'GROUP' and ${a.id} = sub.account_id) or (sub.store_type = 'DIRECTORY' and ${a.dirId} = sub.account_store_id)
          where ${a.username} = ${username}
      """.map(UserAccountRepository(a)).list.apply()
  }

}
