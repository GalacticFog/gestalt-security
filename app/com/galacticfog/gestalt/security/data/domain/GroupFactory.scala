package com.galacticfog.gestalt.security.data.domain

import java.util.UUID
import com.galacticfog.gestalt.security.data.model._
import scalikejdbc._

import scala.util.Try

object GroupFactory extends SQLSyntaxSupport[UserGroupRepository] {

  override val autoSession = AutoSession

  def create(name: String, dirId: UUID): Try[UserGroupRepository] = {
    Try {
      UserGroupRepository.create(id = UUID.randomUUID(), dirId = dirId, name = name, disabled = false)
    }
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
