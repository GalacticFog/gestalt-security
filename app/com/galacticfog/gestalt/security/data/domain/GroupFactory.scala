package com.galacticfog.gestalt.security.data.domain

import java.util.UUID

import com.galacticfog.gestalt.io.util.PatchOp
import com.galacticfog.gestalt.security.api.errors.{ConflictException, BadRequestException}
import com.galacticfog.gestalt.security.data.model._
import org.postgresql.util.PSQLException
import play.api.libs.json.JsResult
import scalikejdbc._
import scalikejdbc.TxBoundary.Try._

import scala.util.{Failure, Success, Try}

trait GroupFactoryDelegate {
  def find(groupId: UUID)(implicit session: DBSession): Option[UserGroupRepository]
  def delete(groupId: UUID)(implicit session: DBSession): Boolean
  def create(name: String, description: Option[String], dirId: UUID, maybeParentOrg: Option[UUID])(implicit session: DBSession): Try[UserGroupRepository]
  def removeAccountFromGroup(groupId: UUID, accountId: UUID)(implicit session: DBSession): Unit
  def addAccountToGroup(groupId: UUID, accountId: UUID)(implicit session: DBSession): Try[GroupMembershipRepository]
  def listGroupAccounts(groupId: UUID)(implicit session: DBSession): Seq[UserAccountRepository]
}

object GroupFactory extends SQLSyntaxSupport[UserGroupRepository] with GroupFactoryDelegate {

  def instance = this

  override val autoSession = AutoSession

  def find(groupId: UUID)(implicit session: DBSession = autoSession): Option[UserGroupRepository] = {
    // TODO - for all directories, find group by ID.
    UserGroupRepository.find(groupId)
  }

  def findGroupByName(orgId: UUID, groupName: String)(implicit session: DBSession = autoSession): Try[List[UserGroupRepository]] = {
    // TODO - for all directories
    Success(List.empty[UserGroupRepository])
  }

  def delete(groupId: UUID)(implicit session: DBSession = autoSession): Boolean = {
    find(groupId) match {
      case Some(grp) =>
        UserGroupRepository.destroy(grp)
        true
      case None =>
        false
    }
  }


  def create(name: String,
             description: Option[String],
             dirId: UUID,
             maybeParentOrg: Option[UUID])
            (implicit session: DBSession = autoSession): Try[UserGroupRepository] = {
    Try {
      UserGroupRepository.create(
        id = UUID.randomUUID(),
        dirId = dirId,
        name = name,
        disabled = false,
        parentOrg = maybeParentOrg,
        description = description
      )
    } recoverWith {
      case t: PSQLException if (t.getSQLState == "23505" || t.getSQLState == "23514") =>
        t.getServerErrorMessage.getConstraint match {
          case "account_group_dir_id_name_key" => Failure(ConflictException(
            resource = "",
            message = "group name already exists in directory",
            developerMessage = "A group with the specified name already exists in the specified directory."
          ))
          case _ => Failure(t)
        }
    }
  }

  /**
    * Deep-query of all application groups, with wildcard matching.
    *
    * An application group is a group that is:
    *   - directly assigned to the app, or
    *   - in an org assigned to the app
    *
    * @param appId the specified app
    * @param nameQuery query parameter for the search, which may include '*' wildcards
    * @param session
    * @return All application groups matching the query
    */
  def lookupAppGroups(appId: UUID, nameQuery: String)
                     (implicit session: DBSession = autoSession): Seq[UserGroupRepository] = {
    val (groupMappings, dirMappings) = AppFactory.listAccountStoreMappings(appId).partition(_.storeType == "GROUP")
    // indirect groups, via their directories
    val dirGroups = dirMappings
      .flatMap( asm => DirectoryFactory.find(asm.accountStoreId.asInstanceOf[UUID]) )
      .flatMap ( _.lookupGroups(nameQuery) )
    // direct groups, but through the directory first (gives unshadowing a chance to happen)
    val appGroupsFromMappings = groupMappings
      .flatMap( asm => GroupFactory.find(asm.accountStoreId.asInstanceOf[UUID]))
    val appGroups = appGroupsFromMappings
      .flatMap( grp => DirectoryFactory.find(grp.dirId.asInstanceOf[UUID]) )
      .flatMap( _.lookupGroups(nameQuery) )
      .intersect( appGroupsFromMappings )
    (appGroups ++ dirGroups).distinct
  }

  def listByDirectoryId(dirId: UUID)(implicit session: DBSession = autoSession): Seq[UserGroupRepository] = {
    UserGroupRepository.findAllBy(sqls"dir_id = ${dirId}")
  }

  def listGroupAccounts(groupId: UUID)(implicit session: DBSession = autoSession): Seq[UserAccountRepository] = {
    val (acc, axg) = (
      UserAccountRepository.syntax("acc"),
      GroupMembershipRepository.syntax("axg")
      )
    sql"""select ${acc.result.*}
          from ${UserAccountRepository.as(acc)},${GroupMembershipRepository.as(axg)}
          where ${axg.groupId} = ${groupId} and ${axg.accountId} = ${acc.id}
      """.map(UserAccountRepository(acc)).list.apply()
  }

  def listAccountGroups(accountId: UUID)(implicit session: DBSession = autoSession): Seq[UserGroupRepository] = {
    val (grp, axg) = (
      UserGroupRepository.syntax("grp"),
      GroupMembershipRepository.syntax("axg")
      )
    sql"""select ${grp.result.*}
          from ${UserGroupRepository.as(grp)},${GroupMembershipRepository.as(axg)}
          where ${axg.accountId} = ${accountId} and ${axg.groupId} = ${grp.id}
      """.map(UserGroupRepository(grp)).list.apply()
  }

  def removeAccountFromGroup(groupId: UUID, accountId: UUID)(implicit session: DBSession = autoSession): Unit = {
//    UserGroupRepository.find(groupId) match {
//      case None => throw new BadRequestException(
//        resource = s"/groups/${groupId}",
//        message = "cannot remove account to non-existent group",
//        developerMessage = "Cannot remove account from non-existent group. Verify that the correct group ID was provided."
//      )
//      case Some(group) => UserAccountRepository.find(accountId) match {
//        case None => throw new BadRequestException(
//          resource = s"/accounts/${groupId}",
//          message = "cannot remove non-existent account from group",
//          developerMessage = "Cannot remove non-existent account from group. Verify that the correct account ID was provided."
//        )
//        case Some(account) =>
//          if (group.dirId.asInstanceOf[UUID] != account.dirId.asInstanceOf[UUID]) throw new BadRequestException(
//            resource = s"/groups/${groupId}",
//            message = "account and group were not in the same directory",
//            developerMessage = "Account and group were not in the same directory. Removing an account from a group requires that they are contained in the same directory."
//          )
//          GroupMembershipRepository.destroy(GroupMembershipRepository(
//            accountId = accountId,
//            groupId = groupId
//          ))
//      }
//    }
    GroupMembershipRepository.destroy(GroupMembershipRepository(
      accountId = accountId,
      groupId = groupId
    ))
  }

  def updateGroupMembership(groupId: UUID, payload: Seq[PatchOp])(implicit session: DBSession = autoSession): Try[Seq[UserAccountRepository]] = {
    DB localTx { implicit session =>
      val ops = payload map { p => for {
        accountId <- Try{p.value.as[UUID]}
        addRemove <- p.op.toLowerCase match {
          case "add" => addAccountToGroup(groupId, accountId) map {_.accountId.asInstanceOf[UUID]}
          case "remove" => Try {
            removeAccountFromGroup(groupId, accountId)
            accountId
          }
          case _ => Failure(BadRequestException("", "invalid op", "group membership update supports only operations add and remove"))
        }
      } yield addRemove}
      Try{ops map {_.get}} map {_ => listGroupAccounts(groupId)}
    }
  }

  def addAccountToGroup(groupId: UUID, accountId: UUID)(implicit session: DBSession = autoSession): Try[GroupMembershipRepository] = {
    UserGroupRepository.find(groupId) match {
      case None => Failure(BadRequestException(
        resource = s"/groups/${groupId}",
        message = "cannot add account to non-existent group",
        developerMessage = "Cannot add account to non-existent group. Verify that the correct group ID was provided."
      ))
      case Some(group) => UserAccountRepository.find(accountId) match {
        case None => Failure(BadRequestException(
          resource = s"/accounts/${groupId}",
          message = "cannot add non-existent account to group",
          developerMessage = "Cannot add non-existent account to group. Verify that the correct account ID was provided."
        ))
        case Some(account) => if (group.dirId.asInstanceOf[UUID] != account.dirId.asInstanceOf[UUID]) Failure(BadRequestException(
          resource = s"/groups/${groupId}",
          message = "account and group were not in the same directory",
          developerMessage = "Account and group were not in the same directory. Adding an account to a group requires that they are contained in the same directory."
        ))
        Try(GroupMembershipRepository.create(
          accountId = accountId,
          groupId = groupId
        ))
      }
    }
  }

  def directoryLookup(dirId: UUID, groupName: String)(implicit session: DBSession = autoSession): Option[UserGroupRepository] = {
    UserGroupRepository.findBy(sqls"dir_id = ${dirId} and name = ${groupName}")
  }

  def getAppGroupMapping(appId: UUID, groupId: UUID)(implicit session: DBSession = autoSession): Option[UserGroupRepository] = {
    val (grp, asm) = (
      UserGroupRepository.syntax("grp"),
      AccountStoreMappingRepository.syntax("asm")
      )
    sql"""select distinct ${grp.result.*}
          from ${UserGroupRepository.as(grp)},${AccountStoreMappingRepository.as(asm)}
          where (${asm.appId} = ${appId} and ${asm.storeType} = 'GROUP' and ${grp.id} = ${asm.accountStoreId} and ${grp.id} = ${groupId}) OR
                (${asm.appId} = ${appId} and ${asm.storeType} = 'DIRECTORY' and ${grp.dirId} = ${asm.accountStoreId} and ${grp.id} = ${groupId})
      """.map(UserGroupRepository(grp)).list.apply().headOption
  }

  /*
    This is all groups where a user in the group would be auth'd in the app
    Therefore, it's
    - all groups directly assigned to the app
    - all groups in a directory that is assigned to the app
    The latter is because any user in the directory is assigned to the app, so that any user in any group in the
    directory (therefore, a user in the directory) is in the app.
   */
  def queryShadowedAppGroups(appId: UUID, nameQuery: Option[String])(implicit session: DBSession = autoSession): Seq[UserGroupRepository]  = {
    val (grp, asm) = (
      UserGroupRepository.syntax("grp"),
      AccountStoreMappingRepository.syntax("asm")
      )
    withSQL {
      select(sqls.distinct(grp.result.*))
        .from(UserGroupRepository as grp)
        .innerJoin(AccountStoreMappingRepository as asm)
        .on(sqls"""(${asm.appId} = ${appId} and ${asm.storeType} = 'GROUP' and ${grp.id} = ${asm.accountStoreId})
                OR (${asm.appId} = ${appId} and ${asm.storeType} = 'DIRECTORY' and ${grp.dirId} = ${asm.accountStoreId})""")
        .where(sqls.toAndConditionOpt(
          nameQuery.map(q => sqls.like(grp.name, q.replace("*","%")))
        ))
    }.map(UserGroupRepository(grp)).list.apply()
  }

  // TODO: does this need to be deleted?
  def queryShadowedDirectoryGroups(dirId: Option[UUID], nameQuery: Option[String])
                                  (implicit session: DBSession = autoSession): List[UserGroupRepository] = {
    val g = UserGroupRepository.syntax("g")
    withSQL {
      select
        .from(UserGroupRepository as g)
        .where(sqls.toAndConditionOpt(
          dirId.map(id => sqls.eq(g.dirId, id)),
          nameQuery.map(q => sqls.like(g.name, q.replace("*","%")))
        ))
    }.map{UserGroupRepository(g)}.list.apply()
  }


}
