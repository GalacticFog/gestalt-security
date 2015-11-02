package com.galacticfog.gestalt.security.data.domain

import java.util.UUID

import com.galacticfog.gestalt.security.api.GestaltBasicCredsToken
import com.galacticfog.gestalt.security.data.model._
import controllers.GestaltHeaderAuthentication
import org.mindrot.jbcrypt.BCrypt
import play.api.Logger
import play.api.libs.json.JsValue
import play.api.mvc.RequestHeader
import scalikejdbc._

import scala.collection.GenTraversableOnce
import scala.util.{Try}

object AccountFactory extends SQLSyntaxSupport[UserAccountRepository] {

  def listByDirectoryId(dirId: UUID): List[UserAccountRepository] = {
    UserAccountRepository.findAllBy(sqls"dir_id=${dirId}")
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

  def frameworkAuth(appId: UUID, request: RequestHeader): Option[UserAccountRepository] = {
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

  def authenticate(appId: UUID, authInfo: JsValue): Option[UserAccountRepository] = {
    val authAttempt = for {
      username <- (authInfo \ "username").asOpt[String].toSeq
      password <- (authInfo \ "password").asOpt[String].toSeq
      acc <- findAppUsersByUsername(appId,username)
      if checkPassword(account=acc, plaintext=password)
    } yield acc
    authAttempt.headOption // first success is good enough
  }

  override val autoSession = AutoSession

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

  def listAppGrants(appId: UUID, username: String): Try[Seq[RightGrantRepository]] = {
    ???
//    findAppUser(appId, username).headOption match {
//      case Some(account) => Try{RightGrantFactory.listRights(appId, account.accountId)}
//      case None => Failure(ResourceNotFoundException(
//        resource = "account",
//        message = "could not locate application account",
//        developerMessage = "Could not location application account while looking up application grants. Ensure that the specified username corresponds to an account that is assigned to the specified application."
//      ))
//    }
  }

  def getAppGrant(appId: UUID, username: String, grantName: String): Try[RightGrantRepository] = {
    ???
//    listAppGrants(appId, username) flatMap {
//      _.filter(_.grantName == grantName) match {
//        case Nil => Failure(ResourceNotFoundException(
//          resource = "rightgrant",
//          message = "right grant not found",
//          developerMessage = "Could not location a right grant with the specified grant name, for the specified account and application."
//        ))
//        case list => Success(list.head)
//      }
//    }
  }

  def deleteAppGrant(appId: UUID, username: String, grantName: String): Try[Boolean] = {
    ???
//    listAppGrants(appId: String, username: String) flatMap {
//      _.filter(_.grantName == grantName) match {
//        case Nil => Success(false)
//        case list =>
//          list foreach {_.destroy()}
//          Success(true)
//      }
//    }
  }

  def updateAppGrant(appId: UUID, username: String, grantName: String, body: JsValue): Try[RightGrantRepository] = {
    ???
//    Try{body.as[GestaltRightGrant]} flatMap { newGrant =>
//      if (newGrant.grantName != grantName) Failure(new RuntimeException("payload grantName does not match URL"))
//      else {
//        getAppGrant(appId,username,grantName) flatMap {
//          existing => Try{ existing.copy(grantValue = newGrant.grantValue).save() }
//        } recoverWith {
//          case grantNotFound: ResourceNotFoundException if grantNotFound.resource == "rightgrant" => Try { RightGrantRepository.create(
//            grantId = SecureIdGenerator.genId62(RightGrantFactory.RIGHT_ID_LEN),
//            appId = appId,
//            accountId = Some(findAppUser(appId, username).head.accountId),
//            groupId = None,
//            grantName = grantName,
//            grantValue = newGrant.grantValue
//          ) }
//        }
//      }
//    }
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
