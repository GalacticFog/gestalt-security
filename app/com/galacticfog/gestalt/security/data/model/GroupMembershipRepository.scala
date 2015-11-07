package com.galacticfog.gestalt.security.data.model

import scalikejdbc._

case class GroupMembershipRepository(
  accountId: Any,
  groupId: Any) {

  def save()(implicit session: DBSession = GroupMembershipRepository.autoSession): GroupMembershipRepository = GroupMembershipRepository.save(this)(session)

  def destroy()(implicit session: DBSession = GroupMembershipRepository.autoSession): Unit = GroupMembershipRepository.destroy(this)(session)

}


object GroupMembershipRepository extends SQLSyntaxSupport[GroupMembershipRepository] {

  override val schemaName = Some("public")

  override val tableName = "account_x_group"

  override val columns = Seq("account_id", "group_id")

  def apply(gmr: SyntaxProvider[GroupMembershipRepository])(rs: WrappedResultSet): GroupMembershipRepository = apply(gmr.resultName)(rs)
  def apply(gmr: ResultName[GroupMembershipRepository])(rs: WrappedResultSet): GroupMembershipRepository = new GroupMembershipRepository(
    accountId = rs.any(gmr.accountId),
    groupId = rs.any(gmr.groupId)
  )

  val gmr = GroupMembershipRepository.syntax("gmr")

  override val autoSession = AutoSession

  def find(accountId: Any, groupId: Any)(implicit session: DBSession = autoSession): Option[GroupMembershipRepository] = {
    withSQL {
      select.from(GroupMembershipRepository as gmr).where.eq(gmr.accountId, accountId).and.eq(gmr.groupId, groupId)
    }.map(GroupMembershipRepository(gmr.resultName)).single.apply()
  }

  def findAll()(implicit session: DBSession = autoSession): List[GroupMembershipRepository] = {
    withSQL(select.from(GroupMembershipRepository as gmr)).map(GroupMembershipRepository(gmr.resultName)).list.apply()
  }

  def countAll()(implicit session: DBSession = autoSession): Long = {
    withSQL(select(sqls.count).from(GroupMembershipRepository as gmr)).map(rs => rs.long(1)).single.apply().get
  }

  def findBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Option[GroupMembershipRepository] = {
    withSQL {
      select.from(GroupMembershipRepository as gmr).where.append(where)
    }.map(GroupMembershipRepository(gmr.resultName)).single.apply()
  }

  def findAllBy(where: SQLSyntax)(implicit session: DBSession = autoSession): List[GroupMembershipRepository] = {
    withSQL {
      select.from(GroupMembershipRepository as gmr).where.append(where)
    }.map(GroupMembershipRepository(gmr.resultName)).list.apply()
  }

  def countBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Long = {
    withSQL {
      select(sqls.count).from(GroupMembershipRepository as gmr).where.append(where)
    }.map(_.long(1)).single.apply().get
  }

  def create(
    accountId: Any,
    groupId: Any)(implicit session: DBSession = autoSession): GroupMembershipRepository = {
    withSQL {
      insert.into(GroupMembershipRepository).columns(
        column.accountId,
        column.groupId
      ).values(
        accountId,
        groupId
      )
    }.update.apply()

    GroupMembershipRepository(
      accountId = accountId,
      groupId = groupId)
  }

  def batchInsert(entities: Seq[GroupMembershipRepository])(implicit session: DBSession = autoSession): Seq[Int] = {
    val params: Seq[Seq[(Symbol, Any)]] = entities.map(entity => 
      Seq(
        'accountId -> entity.accountId,
        'groupId -> entity.groupId))
        SQL("""insert into account_x_group(
        account_id,
        group_id
      ) values (
        {accountId},
        {groupId}
      )""").batchByName(params: _*).apply()
    }

  def save(entity: GroupMembershipRepository)(implicit session: DBSession = autoSession): GroupMembershipRepository = {
    withSQL {
      update(GroupMembershipRepository).set(
        column.accountId -> entity.accountId,
        column.groupId -> entity.groupId
      ).where.eq(column.accountId, entity.accountId).and.eq(column.groupId, entity.groupId)
    }.update.apply()
    entity
  }

  def destroy(entity: GroupMembershipRepository)(implicit session: DBSession = autoSession): Unit = {
    withSQL { delete.from(GroupMembershipRepository).where.eq(column.accountId, entity.accountId).and.eq(column.groupId, entity.groupId) }.update.apply()
  }

}
