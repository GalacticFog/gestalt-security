package com.galacticfog.gestalt.security.data.domain

import java.util.UUID
import com.galacticfog.gestalt.security.api.errors.BadRequestException
import com.galacticfog.gestalt.security.data.model._
import scalikejdbc._

object GroupFactory extends SQLSyntaxSupport[UserGroupRepository] {

  override val autoSession = AutoSession

  def create(name: String, dirId: UUID, parentOrg: UUID)(implicit session: DBSession = autoSession): UserGroupRepository = {
    UserGroupRepository.create(id = UUID.randomUUID(), dirId = dirId, name = name, disabled = false, parentOrg = Some(parentOrg))
  }

  def listByDirectoryId(dirId: UUID)(implicit session: DBSession = autoSession): Seq[UserGroupRepository] = {
    UserGroupRepository.findAllBy(sqls"dir_id = ${dirId}")
  }

  def listAccountGroups(orgId: UUID, accountId: UUID)(implicit session: DBSession = autoSession): Seq[UserGroupRepository] = {
    val (grp, axg) = (
      UserGroupRepository.syntax("grp"),
      GroupMembershipRepository.syntax("axg")
      )
    sql"""select ${grp.result.*}
          from ${UserGroupRepository.as(grp)},${GroupMembershipRepository.as(axg)}
          where ${axg.accountId} = ${accountId} and ${axg.groupId} = ${grp.id}
      """.map(UserGroupRepository(grp)).list.apply()
  }

  def addAccountToGroup(groupId: UUID, accountId: UUID)(implicit session: DBSession = autoSession): GroupMembershipRepository = {
    UserGroupRepository.find(groupId) match {
      case None => throw new BadRequestException(
        resource = s"/groups/${groupId}",
        message = "cannot add account to non-existent group",
        developerMessage = "Cannot add account to non-existent group. Verify that the correct group ID was provided."
      )
      case Some(group) => UserAccountRepository.find(accountId) match {
        case None => throw new BadRequestException(
          resource = s"/accounts/${groupId}",
          message = "cannot add non-existent account to group",
          developerMessage = "Cannot add non-existent account to group. Verify that the correct account ID was provided."
        )
        case Some(account) => if (group.dirId.asInstanceOf[UUID] != account.dirId.asInstanceOf[UUID]) throw new BadRequestException(
          resource = s"/groups/${groupId}",
          message = "account and group were not in the same directory",
          developerMessage = "Account and group were not in the same directory. Adding an account to a group requires that they are contained in the same directory."
        )
        GroupMembershipRepository.create(
          accountId = accountId,
          groupId = groupId
        )
      }
    }
  }

  def directoryLookup(dirId: UUID, groupName: String)(implicit session: DBSession = autoSession): Option[UserGroupRepository] = {
    UserGroupRepository.findBy(sqls"dir_id = ${dirId} and name = ${groupName}")
  }

  def getAppGroupMapping(appId: UUID, groupId: UUID)(implicit session: DBSession = autoSession): Option[UserGroupRepository] = {
    // TODO: needs to be optimized
    val (grp, asm) = (
      UserGroupRepository.syntax("grp"),
      AccountStoreMappingRepository.syntax("asm")
      )
    sql"""select distinct ${grp.result.*}
          from ${UserGroupRepository.as(grp)},${AccountStoreMappingRepository.as(asm)}
          where ${asm.appId} = ${appId} and ${asm.storeType} = 'GROUP' and ${grp.id} = ${asm.accountStoreId} and ${grp.id} = ${groupId}
      """.map(UserGroupRepository(grp)).list.apply().headOption
  }

  def listAppGroupMappings(appId: UUID)(implicit session: DBSession = autoSession): Seq[UserGroupRepository]  = {
    val (grp, asm) = (
      UserGroupRepository.syntax("grp"),
      AccountStoreMappingRepository.syntax("asm")
      )
    sql"""select ${grp.result.*}
          from ${UserGroupRepository.as(grp)},${AccountStoreMappingRepository.as(asm)}
          where ${asm.appId} = ${appId} and ${asm.storeType} = 'GROUP' and ${grp.id} = ${asm.accountStoreId}
      """.map(UserGroupRepository(grp)).list.apply()
  }

}
