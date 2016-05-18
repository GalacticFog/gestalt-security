package com.galacticfog.gestalt.security.data.domain

import java.util.UUID
import com.galacticfog.gestalt.io.util.PatchOp
import com.galacticfog.gestalt.security.api.errors.BadRequestException
import com.galacticfog.gestalt.security.data.model._
import play.api.libs.json.JsResult
import scalikejdbc._

import scala.util.{Failure, Try}

object GroupFactory extends SQLSyntaxSupport[UserGroupRepository] {


  override val autoSession = AutoSession

  def find(groupId: UUID)(implicit session: DBSession = autoSession): Option[UserGroupRepository] = {
    UserGroupRepository.find(groupId)
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


  def create(name: String, description: Option[String], dirId: UUID, parentOrg: UUID)(implicit session: DBSession = autoSession): Try[UserGroupRepository] = {
    Try(UserGroupRepository.create(id = UUID.randomUUID(), dirId = dirId, name = name, disabled = false, parentOrg = Some(parentOrg), description = description))
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
    UserGroupRepository.find(groupId) match {
      case None => throw new BadRequestException(
        resource = s"/groups/${groupId}",
        message = "cannot remove account to non-existent group",
        developerMessage = "Cannot remove account from non-existent group. Verify that the correct group ID was provided."
      )
      case Some(group) => UserAccountRepository.find(accountId) match {
        case None => throw new BadRequestException(
          resource = s"/accounts/${groupId}",
          message = "cannot remove non-existent account from group",
          developerMessage = "Cannot remove non-existent account from group. Verify that the correct account ID was provided."
        )
        case Some(account) => if (group.dirId.asInstanceOf[UUID] != account.dirId.asInstanceOf[UUID]) throw new BadRequestException(
          resource = s"/groups/${groupId}",
          message = "account and group were not in the same directory",
          developerMessage = "Account and group were not in the same directory. Removing an account from a group requires that they are contained in the same directory."
        )
          GroupMembershipRepository.destroy(GroupMembershipRepository(
            accountId = accountId,
            groupId = groupId
          ))
      }
    }
  }

  def updateGroupMembership(groupId: UUID, payload: Seq[PatchOp])(implicit session: DBSession = autoSession): Seq[UserAccountRepository] = {
    DB localTx { implicit session =>
      payload foreach {
        p =>
          val accountId = p.value.as[UUID]
          p.op.toLowerCase match {
          case "add" => addAccountToGroup(groupId, accountId)
          case "remove" => removeAccountFromGroup(groupId, accountId)
        }
      }
      listGroupAccounts(groupId)
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
  def listAppGroups(appId: UUID)(implicit session: DBSession = autoSession): Seq[UserGroupRepository]  = {
    val (grp, asm) = (
      UserGroupRepository.syntax("grp"),
      AccountStoreMappingRepository.syntax("asm")
      )
    sql"""select distinct ${grp.result.*}
          from ${UserGroupRepository.as(grp)},${AccountStoreMappingRepository.as(asm)}
          where (${asm.appId} = ${appId} and ${asm.storeType} = 'GROUP' and ${grp.id} = ${asm.accountStoreId}) OR
                (${asm.appId} = ${appId} and ${asm.storeType} = 'DIRECTORY' and ${grp.dirId} = ${asm.accountStoreId})
      """.map(UserGroupRepository(grp)).list.apply()
  }

}
