package com.galacticfog.gestalt.security.data.domain

import com.galacticfog.gestalt.security.data.model.{UserGroupRepository, GroupMembershipRepository, AppGroupAssignmentRepository, AppAccountAssignmentRepository, UserAccountRepository}
import play.api.libs.json.JsValue
import scalikejdbc._

import scala.util.Try

object UserAccountFactory extends SQLSyntaxSupport[UserAccountRepository] {

  def USER_ID_LEN: Int = 24

  def authenticate(appId: String, authInfo: JsValue): Option[UserAccountRepository] = {
    val authAttempt = for {
      username <- (authInfo \ "username").asOpt[String].toSeq
      password <- (authInfo \ "password").asOpt[String].toSeq
      acc <- findAppUser(appId,username)
      if acc.secret.equals(password)
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

  def findAppUser(appId: String, username: String)(implicit session: DBSession = autoSession): List[UserAccountRepository] = {
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
