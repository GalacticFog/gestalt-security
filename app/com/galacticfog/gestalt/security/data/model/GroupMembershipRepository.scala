package com.galacticfog.gestalt.security.data.model

import scalikejdbc._

case class GroupMembershipRepository(
  groupId: String, 
  accountId: String) {

  def save()(implicit session: DBSession = GroupMembershipRepository.autoSession): GroupMembershipRepository = GroupMembershipRepository.save(this)(session)

  def destroy()(implicit session: DBSession = GroupMembershipRepository.autoSession): Unit = GroupMembershipRepository.destroy(this)(session)

}
      

object GroupMembershipRepository extends SQLSyntaxSupport[GroupMembershipRepository] {

  override val schemaName = Some("public")

  override val tableName = "account_x_group"

  override val columns = Seq("group_id", "account_id")

  def apply(gmr: SyntaxProvider[GroupMembershipRepository])(rs: WrappedResultSet): GroupMembershipRepository = apply(gmr.resultName)(rs)
  def apply(gmr: ResultName[GroupMembershipRepository])(rs: WrappedResultSet): GroupMembershipRepository = new GroupMembershipRepository(
    groupId = rs.get(gmr.groupId),
    accountId = rs.get(gmr.accountId)
  )
      
  val gmr = GroupMembershipRepository.syntax("gmr")

  override val autoSession = AutoSession

  def find(accountId: String, groupId: String)(implicit session: DBSession = autoSession): Option[GroupMembershipRepository] = {
    withSQL {
      select.from(GroupMembershipRepository as gmr).where.eq(gmr.accountId, accountId).and.eq(gmr.groupId, groupId)
    }.map(GroupMembershipRepository(gmr.resultName)).single.apply()
  }
          
  def findAll()(implicit session: DBSession = autoSession): List[GroupMembershipRepository] = {
    withSQL(select.from(GroupMembershipRepository as gmr)).map(GroupMembershipRepository(gmr.resultName)).list.apply()
  }
          
  def countAll()(implicit session: DBSession = autoSession): Long = {
    withSQL(select(sqls"count(1)").from(GroupMembershipRepository as gmr)).map(rs => rs.long(1)).single.apply().get
  }
          
  def findBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Option[GroupMembershipRepository] = {
    withSQL {
      select.from(GroupMembershipRepository as gmr).where.append(sqls"${where}")
    }.map(GroupMembershipRepository(gmr.resultName)).single.apply()
  }
      
  def findAllBy(where: SQLSyntax)(implicit session: DBSession = autoSession): List[GroupMembershipRepository] = {
    withSQL {
      select.from(GroupMembershipRepository as gmr).where.append(sqls"${where}")
    }.map(GroupMembershipRepository(gmr.resultName)).list.apply()
  }
      
  def countBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Long = {
    withSQL {
      select(sqls"count(1)").from(GroupMembershipRepository as gmr).where.append(sqls"${where}")
    }.map(_.long(1)).single.apply().get
  }
      
  def create(
    groupId: String,
    accountId: String)(implicit session: DBSession = autoSession): GroupMembershipRepository = {
    withSQL {
      insert.into(GroupMembershipRepository).columns(
        column.groupId,
        column.accountId
      ).values(
        groupId,
        accountId
      )
    }.update.apply()

    GroupMembershipRepository(
      groupId = groupId,
      accountId = accountId)
  }

  def save(entity: GroupMembershipRepository)(implicit session: DBSession = autoSession): GroupMembershipRepository = {
    withSQL {
      update(GroupMembershipRepository).set(
        column.groupId -> entity.groupId,
        column.accountId -> entity.accountId
      ).where.eq(column.accountId, entity.accountId).and.eq(column.groupId, entity.groupId)
    }.update.apply()
    entity
  }
        
  def destroy(entity: GroupMembershipRepository)(implicit session: DBSession = autoSession): Unit = {
    withSQL { delete.from(GroupMembershipRepository).where.eq(column.accountId, entity.accountId).and.eq(column.groupId, entity.groupId) }.update.apply()
  }
        
}
