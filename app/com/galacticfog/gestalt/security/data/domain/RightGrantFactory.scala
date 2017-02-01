package com.galacticfog.gestalt.security.data.domain

import java.util.UUID

import com.galacticfog.gestalt.security.api.GestaltGrantCreate
import com.galacticfog.gestalt.security.api.errors.BadRequestException
import com.galacticfog.gestalt.security.data.model.{RightGrantRepository, UserGroupRepository, GroupMembershipRepository}
import org.postgresql.util.PSQLException
import play.api.Logger
import scalikejdbc._
import scalikejdbc.TxBoundary.Try._

import scala.util.{Failure, Try}

object RightGrantFactory extends SQLSyntaxSupport[RightGrantRepository] {


  override val autoSession = AutoSession

  def recoverRightGrantCreate: PartialFunction[Throwable, Try[RightGrantRepository]] = {
    case t: PSQLException if t.getSQLState == "23505" || t.getSQLState == "23514" =>
      t.getServerErrorMessage.getConstraint match {
        case "right_grant_name_nonempty" => Failure(BadRequestException(
          resource = "",
          message = "right grant must be non-empty without leading or trailing spaces",
          developerMessage = "Right grant name may not have leading or trailing spaces, and must be non-empty."
        ))
        case _ => Failure(t)
      }
  }

  def addRightToGroup(appId: UUID,
                      groupId: UUID,
                      right: GestaltGrantCreate)
                     (implicit session: DBSession = autoSession): Try[RightGrantRepository] =
  {
    Try { RightGrantRepository.create(
      grantId = UUID.randomUUID,
      appId = appId,
      groupId = Some(groupId),
      grantName = right.grantName,
      grantValue = right.grantValue
    ) } recoverWith recoverRightGrantCreate
  }

  def addRightToAccount(appId: UUID,
                        accountId: UUID,
                        right: GestaltGrantCreate)
                       (implicit session: DBSession = autoSession): Try[RightGrantRepository] = {
    Try { RightGrantRepository.create(
      grantId = UUID.randomUUID,
      appId = appId,
      accountId = Some(accountId),
      grantName = right.grantName,
      grantValue = right.grantValue
    )} recoverWith recoverRightGrantCreate
  }

  def deleteRightGrant(grantId: UUID)(implicit session: DBSession = autoSession): Boolean = {
    RightGrantRepository.find(grantId) match {
      case None => false
      case Some(grant) =>
        grant.destroy()
        false
    }
  }

  def listAccountRights(appId: UUID, accountId: UUID)(implicit session: DBSession = autoSession): List[RightGrantRepository] = {
    val (rg, axg) = (RightGrantRepository.syntax("rg"), GroupMembershipRepository.syntax("axg"))
    withSQL {
      select.from(RightGrantRepository as rg)
        .leftJoin(GroupMembershipRepository as axg).on(sqls"axg.account_id = ${accountId}")
        .where(sqls"rg.app_id = ${appId} AND (rg.account_id = ${accountId} OR rg.group_id = axg.group_id)")
    }.map(RightGrantRepository(rg.resultName)).list.apply().distinct
  }

  def listAllRights(appId: UUID)(implicit session: DBSession = autoSession): List[RightGrantRepository] = {
    RightGrantRepository.findAllBy(sqls"app_id = ${appId}")
  }

  def listGroupRights(appId: UUID, groupId: UUID)(implicit session: DBSession = autoSession): List[RightGrantRepository] = {
    RightGrantRepository.findAllBy(sqls"app_id = ${appId} AND group_id = ${groupId}")
  }
}
