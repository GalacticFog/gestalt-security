package com.galacticfog.gestalt.security.data.domain

import java.util.UUID

import com.galacticfog.gestalt.io.util.PatchOp
import com.galacticfog.gestalt.security.api.{GestaltBasicCredsToken, GestaltPasswordCredential, GestaltAccountUpdate}
import com.galacticfog.gestalt.security.api.errors.{CreateConflictException, BadRequestException, ResourceNotFoundException}
import com.galacticfog.gestalt.security.data.model._
import controllers.GestaltHeaderAuthentication
import org.mindrot.jbcrypt.BCrypt
import play.api.Logger
import play.api.mvc.RequestHeader
import scalikejdbc._

import scala.util.{Failure, Success, Try}
import scala.util.matching.Regex

object AccountFactory extends SQLSyntaxSupport[UserAccountRepository] {

  val E164_PHONE_NUMBER: Regex = """^\+\d{10,15}$""".r


  def validatePhoneNumber(phoneNumber: String): Try[String] = {
    Try {
      val stripped = phoneNumber.replaceAll("[- ().]","")
      E164_PHONE_NUMBER.findFirstIn(stripped) match {
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
      case "" => account.secret.equals(plaintext)
      case s: String =>
        Logger.warn("Unsupported password hash method: " + s)
        false
    }
  }

  def updateAccount(account: UserAccountRepository, update: GestaltAccountUpdate)(implicit session: DBSession = autoSession): UserAccountRepository = {
    val cred = update.credential map {_.asInstanceOf[GestaltPasswordCredential].password}
    val newpass = cred map {p => BCrypt.hashpw(p, BCrypt.gensalt())}
    val newEmail = update.email map { email =>
      if (UserAccountRepository.findBy(sqls"email = ${email} and dir_id = ${account.dirId}").isDefined) {
        throw new CreateConflictException(
          resource = s"/accounts/${account.id}",
          message = "email address already exists",
          developerMessage = "The provided email address is already present in the directory containing the account."
        )
      }
      email
    }
    val newUsername = update.username map { username =>
      if (UserAccountRepository.findBy(sqls"username = ${username} and dir_id = ${account.dirId}").isDefined) {
        throw new CreateConflictException(
          resource = s"/accounts/${account.id}",
          message = "username already exists",
          developerMessage = "The provided username is already present in the directory containing the account."
        )
      }
      username
    }
    val newPhoneNumber = update.phoneNumber map { pn =>
      val t = validatePhoneNumber(pn)
      t match {
        case Success(canonicalPN) =>
          if (UserAccountRepository.findBy(sqls"phone_number = ${canonicalPN} and dir_id = ${account.dirId}").isDefined) {
            throw new CreateConflictException(
              resource = s"/accounts/${account.id}",
              message = "phone number already exists",
              developerMessage = "The provided phone number is already present in the directory containing the account."
            )
          }
          canonicalPN
        case Failure(ex) => ex match {
          case br: BadRequestException => throw br.copy(
            resource = s"/accounts/${account.id}"
          )
          case t: Throwable => throw t
        }
      }
    }
    UserAccountRepository.save(
      account.copy(
        username = newUsername getOrElse account.username,
        firstName = update.firstName getOrElse account.firstName,
        lastName = update.lastName getOrElse account.lastName,
        email = newEmail orElse account.email,
        phoneNumber = newPhoneNumber orElse account.phoneNumber,
        hashMethod = if (newpass.isDefined) "bcrypt" else account.hashMethod,
        secret = newpass getOrElse account.secret
      )
    )
  }

  def frameworkAuth(appId: UUID, request: RequestHeader)(implicit session: DBSession = autoSession): Option[UserAccountRepository] = {
    val usernameAuths = for {
      token <- GestaltHeaderAuthentication.extractAuthToken(request).toSeq
      acc <- findAppUsersByUsername(appId,token.username)
      if checkPassword(account = acc, plaintext = token.password)
    } yield acc
    lazy val emailAuths = for {
      token <- GestaltHeaderAuthentication.extractAuthToken(request).toSeq
      acc <- findAppUsersByEmail(appId,token.username)
      if checkPassword(account = acc, plaintext = token.password)
    } yield acc
    // first success is good enough
    usernameAuths.headOption orElse emailAuths.headOption
  }

  def authenticate(appId: UUID, creds: GestaltBasicCredsToken)(implicit session: DBSession = autoSession): Option[UserAccountRepository] = {
    val authAttempt = for {
      acc <- findAppUsersByUsername(appId,creds.username)
      if checkPassword(account=acc, plaintext=creds.password)
    } yield acc
    authAttempt.headOption // first success is good enough
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
      """.map{UserAccountRepository(a)}.list.apply()
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
    RightGrantFactory.listAccountRights(appId, groupId)
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
