package com.galacticfog.gestalt.security.data.model

import scalikejdbc._

case class InitSettingsRepository(
  id: Int,
  instanceUuid: Any,
  initialized: Boolean,
  rootAccount: Option[Any] = None) {

  def save()(implicit session: DBSession = InitSettingsRepository.autoSession): InitSettingsRepository = InitSettingsRepository.save(this)(session)

  def destroy()(implicit session: DBSession = InitSettingsRepository.autoSession): Unit = InitSettingsRepository.destroy(this)(session)

}


object InitSettingsRepository extends SQLSyntaxSupport[InitSettingsRepository] {

  override val schemaName = Some("public")

  override val tableName = "initialization_settings"

  override val columns = Seq("id", "instance_uuid", "initialized", "root_account")

  def apply(isr: SyntaxProvider[InitSettingsRepository])(rs: WrappedResultSet): InitSettingsRepository = apply(isr.resultName)(rs)
  def apply(isr: ResultName[InitSettingsRepository])(rs: WrappedResultSet): InitSettingsRepository = new InitSettingsRepository(
    id = rs.get(isr.id),
    instanceUuid = rs.any(isr.instanceUuid),
    initialized = rs.get(isr.initialized),
    rootAccount = rs.anyOpt(isr.rootAccount)
  )

  val isr = InitSettingsRepository.syntax("isr")

  override val autoSession = AutoSession

  def find(id: Int)(implicit session: DBSession = autoSession): Option[InitSettingsRepository] = {
    withSQL {
      select.from(InitSettingsRepository as isr).where.eq(isr.id, id)
    }.map(InitSettingsRepository(isr.resultName)).single.apply()
  }

  def findAll()(implicit session: DBSession = autoSession): List[InitSettingsRepository] = {
    withSQL(select.from(InitSettingsRepository as isr)).map(InitSettingsRepository(isr.resultName)).list.apply()
  }

  def countAll()(implicit session: DBSession = autoSession): Long = {
    withSQL(select(sqls.count).from(InitSettingsRepository as isr)).map(rs => rs.long(1)).single.apply().get
  }

  def findBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Option[InitSettingsRepository] = {
    withSQL {
      select.from(InitSettingsRepository as isr).where.append(where)
    }.map(InitSettingsRepository(isr.resultName)).single.apply()
  }

  def findAllBy(where: SQLSyntax)(implicit session: DBSession = autoSession): List[InitSettingsRepository] = {
    withSQL {
      select.from(InitSettingsRepository as isr).where.append(where)
    }.map(InitSettingsRepository(isr.resultName)).list.apply()
  }

  def countBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Long = {
    withSQL {
      select(sqls.count).from(InitSettingsRepository as isr).where.append(where)
    }.map(_.long(1)).single.apply().get
  }

  def create(
    id: Int,
    instanceUuid: Any,
    initialized: Boolean,
    rootAccount: Option[Any] = None)(implicit session: DBSession = autoSession): InitSettingsRepository = {
    withSQL {
      insert.into(InitSettingsRepository).columns(
        column.id,
        column.instanceUuid,
        column.initialized,
        column.rootAccount
      ).values(
        id,
        instanceUuid,
        initialized,
        rootAccount
      )
    }.update.apply()

    InitSettingsRepository(
      id = id,
      instanceUuid = instanceUuid,
      initialized = initialized,
      rootAccount = rootAccount)
  }

  def batchInsert(entities: Seq[InitSettingsRepository])(implicit session: DBSession = autoSession): Seq[Int] = {
    val params: Seq[Seq[(Symbol, Any)]] = entities.map(entity => 
      Seq(
        'id -> entity.id,
        'instanceUuid -> entity.instanceUuid,
        'initialized -> entity.initialized,
        'rootAccount -> entity.rootAccount))
        SQL("""insert into initialization_settings(
        id,
        instance_uuid,
        initialized,
        root_account
      ) values (
        {id},
        {instanceUuid},
        {initialized},
        {rootAccount}
      )""").batchByName(params: _*).apply()
    }

  def save(entity: InitSettingsRepository)(implicit session: DBSession = autoSession): InitSettingsRepository = {
    withSQL {
      update(InitSettingsRepository).set(
        column.id -> entity.id,
        column.instanceUuid -> entity.instanceUuid,
        column.initialized -> entity.initialized,
        column.rootAccount -> entity.rootAccount
      ).where.eq(column.id, entity.id)
    }.update.apply()
    entity
  }

  def destroy(entity: InitSettingsRepository)(implicit session: DBSession = autoSession): Unit = {
    withSQL { delete.from(InitSettingsRepository).where.eq(column.id, entity.id) }.update.apply()
  }

}
