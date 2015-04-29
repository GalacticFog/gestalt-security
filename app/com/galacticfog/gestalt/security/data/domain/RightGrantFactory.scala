package com.galacticfog.gestalt.security.data.domain

import com.galacticfog.gestalt.security.data.model.{UserGroupRepository, GroupMembershipRepository, RightGrantRepository}
import play.api.Logger
import scalikejdbc._

object RightGrantFactory extends SQLSyntaxSupport[RightGrantRepository] {
  override val autoSession = AutoSession

  def listRights(appId: String, accountId: String)(implicit session: DBSession = autoSession): List[RightGrantRepository] = {
    Logger.info(s"looking up rights for appId = ${appId}, accountId = ${accountId}")
    val (rg, axg) = (RightGrantRepository.syntax("rg"), GroupMembershipRepository.syntax("axg"))
    withSQL {
      select.from(RightGrantRepository as rg)
        .leftJoin(GroupMembershipRepository as axg).on(sqls"axg.account_id = ${accountId}")
        .where(sqls"rg.app_id = ${appId} AND (rg.account_id = ${accountId} OR rg.group_id = axg.group_id)")
    }.map(RightGrantRepository(rg.resultName)).list.apply()
  }

}
