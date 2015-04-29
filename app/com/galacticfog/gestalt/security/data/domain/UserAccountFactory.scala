package com.galacticfog.gestalt.security.data.domain

import com.galacticfog.gestalt.security.data.model.{UserGroupRepository, GroupMembershipRepository, AppUserStoreRepository, UserAccountRepository}
import play.api.libs.json.JsValue
import scalikejdbc._

object UserAccountFactory extends SQLSyntaxSupport[UserAccountRepository] {

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

  def findAppUser(appId: String, username: String)(implicit session: DBSession = autoSession): List[UserAccountRepository] = {
    val (uar, aus, axg, ug) = (UserAccountRepository.syntax("uar"), AppUserStoreRepository.syntax("aus"),
                               GroupMembershipRepository.syntax("axg"), UserGroupRepository.syntax("ug"))
    withSQL {
      select.from(UserAccountRepository as uar)
        .join(AppUserStoreRepository as aus).on(sqls"aus.app_id = ${appId}")
        .join(UserGroupRepository as ug).on(ug.groupId,aus.groupId)
        .join(GroupMembershipRepository as axg).on(axg.groupId, ug.groupId)
        .where.eq(uar.username, username).and.eq(uar.accountId,axg.accountId)
    }.map(UserAccountRepository(uar.resultName)).list.apply()
  }
}
