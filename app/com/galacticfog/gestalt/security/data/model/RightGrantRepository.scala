package com.galacticfog.gestalt.security.data.model

import scalikejdbc._

case class RightGrantRepository(
  grantId: Any,
  appId: Any,
  accountId: Option[Any] = None,
  groupId: Option[Any] = None,
  grantName: String,
  grantValue: Option[String] = None) {

  def save()(implicit session: DBSession = RightGrantRepository.autoSession): RightGrantRepository = RightGrantRepository.save(this)(session)

  def destroy()(implicit session: DBSession = RightGrantRepository.autoSession): Unit = RightGrantRepository.destroy(this)(session)

}


object RightGrantRepository extends SQLSyntaxSupport[RightGrantRepository] {

  override val schemaName = Some("public")

  override val tableName = "right_grant"

  override val columns = Seq("grant_id", "app_id", "account_id", "group_id", "grant_name", "grant_value")

  def apply(rgr: SyntaxProvider[RightGrantRepository])(rs: WrappedResultSet): RightGrantRepository = apply(rgr.resultName)(rs)
  def apply(rgr: ResultName[RightGrantRepository])(rs: WrappedResultSet): RightGrantRepository = new RightGrantRepository(
    grantId = rs.any(rgr.grantId),
    appId = rs.any(rgr.appId),
    accountId = rs.anyOpt(rgr.accountId),
    groupId = rs.anyOpt(rgr.groupId),
    grantName = rs.get(rgr.grantName),
    grantValue = rs.get(rgr.grantValue)
  )

  val rgr = RightGrantRepository.syntax("rgr")

  override val autoSession = AutoSession

  def find(grantId: Any)(implicit session: DBSession = autoSession): Option[RightGrantRepository] = {
    withSQL {
      select.from(RightGrantRepository as rgr).where.eq(rgr.grantId, grantId)
    }.map(RightGrantRepository(rgr.resultName)).single.apply()
  }

  def findAll()(implicit session: DBSession = autoSession): List[RightGrantRepository] = {
    withSQL(select.from(RightGrantRepository as rgr)).map(RightGrantRepository(rgr.resultName)).list.apply()
  }

  def countAll()(implicit session: DBSession = autoSession): Long = {
    withSQL(select(sqls.count).from(RightGrantRepository as rgr)).map(rs => rs.long(1)).single.apply().get
  }

  def findBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Option[RightGrantRepository] = {
    withSQL {
      select.from(RightGrantRepository as rgr).where.append(where)
    }.map(RightGrantRepository(rgr.resultName)).single.apply()
  }

  def findAllBy(where: SQLSyntax)(implicit session: DBSession = autoSession): List[RightGrantRepository] = {
    withSQL {
      select.from(RightGrantRepository as rgr).where.append(where)
    }.map(RightGrantRepository(rgr.resultName)).list.apply()
  }

  def countBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Long = {
    withSQL {
      select(sqls.count).from(RightGrantRepository as rgr).where.append(where)
    }.map(_.long(1)).single.apply().get
  }

  def create(
    grantId: Any,
    appId: Any,
    accountId: Option[Any] = None,
    groupId: Option[Any] = None,
    grantName: String,
    grantValue: Option[String] = None)(implicit session: DBSession = autoSession): RightGrantRepository = {
    withSQL {
      insert.into(RightGrantRepository).columns(
        column.grantId,
        column.appId,
        column.accountId,
        column.groupId,
        column.grantName,
        column.grantValue
      ).values(
        grantId,
        appId,
        accountId,
        groupId,
        grantName,
        grantValue
      )
    }.update.apply()

    RightGrantRepository(
      grantId = grantId,
      appId = appId,
      accountId = accountId,
      groupId = groupId,
      grantName = grantName,
      grantValue = grantValue)
  }

  def batchInsert(entities: Seq[RightGrantRepository])(implicit session: DBSession = autoSession): Seq[Int] = {
    val params: Seq[Seq[(Symbol, Any)]] = entities.map(entity => 
      Seq(
        'grantId -> entity.grantId,
        'appId -> entity.appId,
        'accountId -> entity.accountId,
        'groupId -> entity.groupId,
        'grantName -> entity.grantName,
        'grantValue -> entity.grantValue))
        SQL("""insert into right_grant(
        grant_id,
        app_id,
        account_id,
        group_id,
        grant_name,
        grant_value
      ) values (
        {grantId},
        {appId},
        {accountId},
        {groupId},
        {grantName},
        {grantValue}
      )""").batchByName(params: _*).apply()
    }

  def save(entity: RightGrantRepository)(implicit session: DBSession = autoSession): RightGrantRepository = {
    withSQL {
      update(RightGrantRepository).set(
        column.grantId -> entity.grantId,
        column.appId -> entity.appId,
        column.accountId -> entity.accountId,
        column.groupId -> entity.groupId,
        column.grantName -> entity.grantName,
        column.grantValue -> entity.grantValue
      ).where.eq(column.grantId, entity.grantId)
    }.update.apply()
    entity
  }

  def destroy(entity: RightGrantRepository)(implicit session: DBSession = autoSession): Unit = {
    withSQL { delete.from(RightGrantRepository).where.eq(column.grantId, entity.grantId) }.update.apply()
  }

}
