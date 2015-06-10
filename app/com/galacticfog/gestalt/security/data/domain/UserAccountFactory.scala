package com.galacticfog.gestalt.security.data.domain

import com.galacticfog.gestalt.security.api.{ResourceNotFoundException, GestaltRightGrant}
import com.galacticfog.gestalt.security.data.model._
import com.galacticfog.gestalt.security.utils.SecureIdGenerator
import org.mindrot.jbcrypt.BCrypt
import play.api.Logger
import play.api.libs.json.JsValue
import scalikejdbc._
import com.galacticfog.gestalt.security.api.json.JsonImports._

import scala.util.{Success, Failure, Try}

object UserAccountFactory extends SQLSyntaxSupport[UserAccountRepository] {

  def USER_ID_LEN: Int = 24

  private[this] def checkPassword(account: UserAccountRepository, plaintext: String): Boolean = {
    account.hashMethod match {
      case "bcrypt" => BCrypt.checkpw(plaintext, account.secret)
      case "" => account.secret.equals(plaintext)
      case s: String =>
        Logger.warn("Unsupported password hash method: " + s)
        false
    }
  }

  def authenticate(appId: String, authInfo: JsValue): Option[UserAccountRepository] = {
    val authAttempt = for {
      username <- (authInfo \ "username").asOpt[String].toSeq
      password <- (authInfo \ "password").asOpt[String].toSeq
      acc <- findAppUser(appId,username)
      if checkPassword(account=acc, plaintext=password)
    } yield acc
    authAttempt.headOption // first success is good enough
  }

  override val autoSession = AutoSession

  def listByAppId(appId: String)(implicit session: DBSession = autoSession): List[UserAccountRepository] = {
    val (uar, appaccount, appgroup, axg, ug) = (
      UserAccountRepository.syntax("uar"),
      AppAccountAssignmentRepository.syntax("appaccount"),
      AppGroupAssignmentRepository.syntax("appgroup"),
      GroupMembershipRepository.syntax("axg"),
      UserGroupRepository.syntax("ug")
      )
    withSQL {
      select(sqls"${uar.result.*}").from(UserAccountRepository as uar)
        .innerJoin(AppAccountAssignmentRepository as appaccount).on(sqls"${appaccount.appId} = ${appId} and ${appaccount.accountId} = ${uar.accountId}")
      .union(
        select(sqls"${uar.result.*}").from(UserAccountRepository as uar)
        .innerJoin(GroupMembershipRepository as axg).on(axg.accountId, uar.accountId)
        .innerJoin(AppGroupAssignmentRepository as appgroup).on(sqls"${appgroup.appId} = ${appId} and ${appgroup.groupId} = ${axg.groupId}")
      )
    }.map(UserAccountRepository(uar.resultName)).list.apply()
  }

  def listAppGrants(appId: String, username: String): Try[Seq[RightGrantRepository]] = {
    findAppUser(appId, username).headOption match {
      case Some(account) => Try{RightGrantFactory.listRights(appId, account.accountId)}
      case None => Failure(ResourceNotFoundException(
        resource = "account",
        message = "could not locate application account",
        developerMessage = "Could not location application account while looking up application grants. Ensure that the specified username corresponds to an account that is assigned to the specified application."
      ))
    }
  }

  def getAppGrant(appId: String, username: String, grantName: String): Try[RightGrantRepository] = {
    listAppGrants(appId, username) flatMap {
      _.filter(_.grantName == grantName) match {
        case Nil => Failure(ResourceNotFoundException(
          resource = "rightgrant",
          message = "right grant not found",
          developerMessage = "Could not location a right grant with the specified grant name, for the specified account and application."
        ))
        case list => Success(list.head)
      }
    }
  }

  def deleteAppGrant(appId: String, username: String, grantName: String): Try[Boolean] = {
    listAppGrants(appId: String, username: String) flatMap {
      _.filter(_.grantName == grantName) match {
        case Nil => Success(false)
        case list =>
          list foreach {_.destroy()}
          Success(true)
      }
    }
  }

  def updateAppGrant(appId: String, username: String, grantName: String, body: JsValue): Try[RightGrantRepository] = {
    Try{body.as[GestaltRightGrant]} flatMap { newGrant =>
      if (newGrant.grantName != grantName) Failure(new RuntimeException("payload grantName does not match URL"))
      else {
        getAppGrant(appId,username,grantName) flatMap {
          existing => Try{ existing.copy(grantValue = newGrant.grantValue).save() }
        } recoverWith {
          case grantNotFound: ResourceNotFoundException if grantNotFound.resource == "rightgrant" => Try { RightGrantRepository.create(
            grantId = SecureIdGenerator.genId62(RightGrantFactory.RIGHT_ID_LEN),
            appId = appId,
            accountId = Some(findAppUser(appId, username).head.accountId),
            groupId = None,
            grantName = grantName,
            grantValue = newGrant.grantValue
          ) }
        }
      }
    }
  }

  private[this] def findAppUser(appId: String, username: String)(implicit session: DBSession = autoSession): List[UserAccountRepository] = {
    val (uar, appaccount, appgroup, axg, ug) = (
      UserAccountRepository.syntax("uar"),
      AppAccountAssignmentRepository.syntax("appaccount"),
      AppGroupAssignmentRepository.syntax("appgroup"),
      GroupMembershipRepository.syntax("axg"),
      UserGroupRepository.syntax("ug")
      )
    withSQL {
      select(sqls"${uar.result.*}").from(UserAccountRepository as uar)
        .innerJoin(AppAccountAssignmentRepository as appaccount).on(sqls"${appaccount.appId} = ${appId} and ${appaccount.accountId} = ${uar.accountId}")
        .where.eq(uar.username,username)
        .union(
          select(sqls"${uar.result.*}").from(UserAccountRepository as uar)
            .innerJoin(GroupMembershipRepository as axg).on(axg.accountId, uar.accountId)
            .innerJoin(AppGroupAssignmentRepository as appgroup).on(sqls"${appgroup.appId} = ${appId} and ${appgroup.groupId} = ${axg.groupId}")
            .where.eq(uar.username,username)
        )
    }.map(UserAccountRepository(uar.resultName)).list.apply()
  }

}
