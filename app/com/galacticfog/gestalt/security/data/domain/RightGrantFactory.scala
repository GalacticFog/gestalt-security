package com.galacticfog.gestalt.security.data.domain

import java.util.UUID

import com.galacticfog.gestalt.security.data.model.{RightGrantRepository, UserGroupRepository, GroupMembershipRepository}
import play.api.Logger
import scalikejdbc._

object RightGrantFactory extends SQLSyntaxSupport[RightGrantRepository] {

  override val autoSession = AutoSession

  def addRightsToGroup(appId: UUID, groupId: UUID, rights: Seq[String])(implicit session: DBSession = autoSession): Seq[RightGrantRepository] = {
    rights map {
      name => RightGrantRepository.create(grantId = UUID.randomUUID, appId = appId, groupId = Some(groupId), grantName = name)
    }
  }

  def addRightsToAccount(appId: UUID, accountId: UUID, rights: Seq[String])(implicit session: DBSession = autoSession): Seq[RightGrantRepository] = {
    rights map {
      name => RightGrantRepository.create(grantId = UUID.randomUUID, appId = appId, accountId = Some(accountId), grantName = name)
    }
  }

  def deleteRightGrant(grantId: UUID)(implicit session: DBSession = autoSession): Boolean = {
    ???
//    RightGrantRepository.find(grantId) match {
//      case None => Success(false)
//      case Some(grant) =>
//        grant.destroy()
//        Success(false)
//    }
  }

  def listRights(appId: UUID, accountId: UUID)(implicit session: DBSession = autoSession): List[RightGrantRepository] = {
    Logger.info(s"looking up rights for appId = ${appId}, accountId = ${accountId}")
    val (rg, axg) = (RightGrantRepository.syntax("rg"), GroupMembershipRepository.syntax("axg"))
    withSQL {
      select.from(RightGrantRepository as rg)
        .leftJoin(GroupMembershipRepository as axg).on(sqls"axg.account_id = ${accountId}")
        .where(sqls"rg.app_id = ${appId} AND (rg.account_id = ${accountId} OR rg.group_id = axg.group_id)")
    }.map(RightGrantRepository(rg.resultName)).list.apply().distinct
  }

}
