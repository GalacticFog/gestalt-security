package com.galacticfog.gestalt.security.data.domain

import java.util.UUID

import com.galacticfog.gestalt.io.util.PatchOp
import com.galacticfog.gestalt.security.api._
import com.galacticfog.gestalt.security.api.errors.{UnknownAPIException, ConflictException, BadRequestException, ResourceNotFoundException}
import com.galacticfog.gestalt.security.data.model._
import controllers.GestaltHeaderAuthentication
import org.mindrot.jbcrypt.BCrypt
import org.postgresql.util.PSQLException
import play.api.Logger
import play.api.mvc.RequestHeader
import scalikejdbc._

import scala.util.{Failure, Try}
import scala.util.matching.Regex

trait AccountFactoryDelegate {
  def createAccount(dirId: UUID, username: String, description: Option[String] = None, email: Option[String] = None, phoneNumber: Option[String] = None,
                    firstName: String, lastName: String, hashMethod: String, salt: String, secret: String, disabled: Boolean)(implicit session: DBSession): Try[UserAccountRepository]
  def directoryLookup(dirId: UUID, username: String)(implicit session: DBSession): Option[UserAccountRepository]
}

object AccountFactory extends SQLSyntaxSupport[UserAccountRepository] with AccountFactoryDelegate {

  val E164_PHONE_NUMBER: Regex = """^\+\d{10,15}$""".r

  def instance = this

  def canonicalE164(phoneNumber: String): String = {
    phoneNumber.replaceAll("[- ().]","")
  }

  def validatePhoneNumber(phoneNumber: String): Try[String] = {
    Try {
      E164_PHONE_NUMBER.findFirstIn(canonicalE164(phoneNumber)) match {
        case Some(validNumber) => validNumber
        case None => throw new BadRequestException(
          resource = "",
          message = "badly formatted phoneNumber",
          developerMessage = s"""Badly formatted phone number. Must match E.164 formatting."""
        )
      }
    }
  }

  override val autoSession = AutoSession

  def disableAccount(accountId: UUID)(implicit session: DBSession = autoSession): Unit = {
    val column = UserAccountRepository.column
    withSQL {
      update(UserAccountRepository).set(column.disabled -> true).where.eq(column.id, accountId)
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

  def directoryLookup(dirId: UUID, username: String)(implicit session: DBSession = autoSession): Option[UserAccountRepository] = {
    UserAccountRepository.findBy(sqls"dir_id=${dirId} and username=${username}")
  }

  def checkPassword(account: UserAccountRepository, plaintext: String): Boolean = {
    account.hashMethod match {
      case "bcrypt" => BCrypt.checkpw(plaintext, account.secret)
      case "disabled" =>
        Logger.info("Account password authenticated marked disabled")
        false
      case "" => account.secret.equals(plaintext)
      case s: String =>
        Logger.warn("Unsupported password hash method: " + s)
        false
    }
  }

  override def createAccount(dirId: UUID,
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
      case t: PSQLException if (t.getSQLState == "23505" || t.getSQLState == "23514") =>
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
      case t: PSQLException if (t.getSQLState == "23505" || t.getSQLState == "23514") =>
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
        case PatchOp("replace", "/username",value)     => acc.copy(username = value.as[String])
        case PatchOp("replace", "/firstName",value)    => acc.copy(firstName = value.as[String])
        case PatchOp("replace", "/lastName",value)     => acc.copy(lastName = value.as[String])
        case PatchOp("replace", "/disabled", value)    => acc.copy(disabled = value.as[Boolean])
        case PatchOp("replace", "/password", value)    => {
          import com.galacticfog.gestalt.security.api.json.JsonImports._
          val newpass = BCrypt.hashpw(value.as[GestaltAccountCredential].asInstanceOf[GestaltPasswordCredential].password, BCrypt.gensalt())
          acc.copy(hashMethod = "bcrypt", secret = newpass)
        }
        case PatchOp("remove",  "/email", _)           => acc.copy(email = None)
        case PatchOp("add",     "/email", value)       => acc.copy(email = Some(value.as[String]))
        case PatchOp("replace", "/email", value)       => acc.copy(email = Some(value.as[String]))
        case PatchOp("remove",  "/phoneNumber", _)     => acc.copy(phoneNumber = None)
        case PatchOp("add",     "/phoneNumber", value) => acc.copy(phoneNumber = Some(value.as[String]))
        case PatchOp("replace", "/phoneNumber", value) => acc.copy(phoneNumber = Some(value.as[String]))
        case _ => throw new BadRequestException(
          resource = "",
          message = "bad PATCH payload for updating account store",
          developerMessage = "The PATCH payload for updating the account store mapping had invalid fields."
        )
      }
    })
    saveAccount(
      patchedAccount.copy(
        phoneNumber = patchedAccount.phoneNumber map canonicalE164
      )
    )
  }

  def updateAccountSDK(account: UserAccountRepository, update: GestaltAccountUpdate)(implicit session: DBSession = autoSession): Try[UserAccountRepository] = {
    val cred = update.credential map {_.asInstanceOf[GestaltPasswordCredential].password}
    val newpass = cred map {p => BCrypt.hashpw(p, BCrypt.gensalt())}
    val newPhoneNumber = update.phoneNumber map canonicalE164
    saveAccount(
      account.copy(
        username = update.username getOrElse account.username,
        firstName = update.firstName getOrElse account.firstName,
        lastName = update.lastName getOrElse account.lastName,
        email = update.email orElse account.email,
        phoneNumber = newPhoneNumber orElse account.phoneNumber,
        hashMethod = if (newpass.isDefined) "bcrypt" else account.hashMethod,
        secret = newpass getOrElse account.secret
      )
    )
  }

  def frameworkAuth(appId: UUID, request: RequestHeader)(implicit session: DBSession = autoSession): Option[UserAccountRepository] = {
    val usernameAuths = for {
      token <- GestaltHeaderAuthentication.extractAuthToken(request).flatMap {_ match {
        case t: GestaltBasicCredentials => Some(t)
        case _ => None
      }}.toSeq
      acc <- AccountFactory.authenticate(appId, GestaltBasicCredsToken(token.username, token.password))
    } yield acc
    // first success is good enough
    usernameAuths.headOption
  }

  def authenticate(appId: UUID, creds: GestaltBasicCredsToken)(implicit session: DBSession = autoSession): Option[UserAccountRepository] = {
    val authAttempt = for {
      acc <- findAppUsersByUsername(appId,creds.username)
      dir <- DirectoryFactory.find(acc.dirId.asInstanceOf[java.util.UUID])
      authedAcc <- if (dir.authenticateAccount(acc, creds.password)) Some(acc) else None
    } yield authedAcc
    val shadowedAccounts = for {
        app <- AppFactory.findByAppId(appId).toSeq
        dir <- DirectoryFactory.listByOrgId(app.orgId.asInstanceOf[UUID]).filter {d => d.isInstanceOf[LDAPDirectory]}
        saccount <- dir.lookupAccountByPrimary(creds.username) if dir.authenticateAccount(saccount, creds.password)  // Creates shadow account if found, then authenticates
    } yield saccount
    // TODO - query all indirect LDAPDirectories
//    val indirectAccounts = for {
//        app <- AppFactory.findByAppId(appId).toSeq
//        grp <- GroupFactory.listAppGroups(appId)
//        dir <- Some(DirectoryFactory.find(grp.dirId)).toSeq
//        saccount <- dir.lookupAccountByPrimary(creds.username) if dir.authenticateAccount(saccount, creds.password) == true  // Creates shadow account if found, then authenticates
//    } yield saccount
    (authAttempt.headOption orElse shadowedAccounts.headOption) // orElse indirectAccounts.headOption
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

  def listByAppId(appId: UUID)(implicit session: DBSession = autoSession): List[UserAccountRepository] = {
    val a = UserAccountRepository.syntax("a")
    sql"""select ${a.result.*}
          from ${UserAccountRepository.as(a)}
          inner join (
            select axg.account_id,asm.account_store_id from account_x_group as axg
              right join account_store_mapping as asm on asm.account_store_id = axg.group_id and asm.store_type = 'GROUP'
              where asm.app_id = ${appId}
          ) as sub on ${a.id} = sub.account_id or ${a.dirId} = sub.account_store_id
          where ${a.disabled} = false
      """.map{UserAccountRepository(a)}.list.apply().distinct
  }

  def listAppAccountGrants(appId: UUID, accountId: UUID)(implicit session: DBSession = autoSession): Seq[RightGrantRepository] = {
    findAppUserByAccountId(appId, accountId) match {
      case Some(account) => RightGrantFactory.listAccountRights(appId, account.id.asInstanceOf[UUID])
      case None => throw new ResourceNotFoundException(
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
            case PatchOp(op,"/grantValue",value) if op.toLowerCase == "add" || op.toLowerCase == "replace" =>
              g.copy(grantValue = Some(value.as[String]))
            case PatchOp("remove","/grantValue",value) =>
              g.copy(grantValue = None)
            case _ => throw new BadRequestException(
              resource = "",
              message = "bad PATCH payload for updating account grant",
              developerMessage = "The PATCH payload for updating the accoutn grant had invalid fields."
            )
          }
        })
        newGrant.save()
      case None => throw new ResourceNotFoundException(
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
            case PatchOp(op,"/grantValue",value) if op.toLowerCase == "add" || op.toLowerCase == "replace" =>
              g.copy(grantValue = Some(value.as[String]))
            case PatchOp("remove","/grantValue",value) =>
              g.copy(grantValue = None)
            case _ => throw new BadRequestException(
              resource = "",
              message = "bad PATCH payload for updating account grant",
              developerMessage = "The PATCH payload for updating the accoutn grant had invalid fields."
            )
          }
        })
        newGrant.save()
      case None => throw new ResourceNotFoundException(
        resource = "",
        message = "grant does not exist",
        developerMessage = "A grant with the given name does not exist for the given account in the given app."
      )
    }
  }

  def listAppUsers(appId: UUID)(implicit session: DBSession = autoSession): List[UserAccountRepository] = {
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
          where ${a.id} = ${accountId} and ${a.disabled} = false
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
          where ${a.email} = ${email} and ${a.disabled} = false
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
          where ${a.username} = ${username} and ${a.disabled} = false
      """.map(UserAccountRepository(a)).list.apply()
  }

}
