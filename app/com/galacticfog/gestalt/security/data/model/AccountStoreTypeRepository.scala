package com.galacticfog.gestalt.security.data.model

import scalikejdbc._

case class AccountStoreTypeRepository(
  id: Long,
  name: String,
  description: Option[String] = None) {

  def save()(implicit session: DBSession = AccountStoreTypeRepository.autoSession): AccountStoreTypeRepository = AccountStoreTypeRepository.save(this)(session)

  def destroy()(implicit session: DBSession = AccountStoreTypeRepository.autoSession): Unit = AccountStoreTypeRepository.destroy(this)(session)

}


object AccountStoreTypeRepository extends SQLSyntaxSupport[AccountStoreTypeRepository] {

  override val schemaName = Some("public")

  override val tableName = "account_store_type"

  override val columns = Seq("id", "name", "description")

  def apply(astr: SyntaxProvider[AccountStoreTypeRepository])(rs: WrappedResultSet): AccountStoreTypeRepository = apply(astr.resultName)(rs)
  def apply(astr: ResultName[AccountStoreTypeRepository])(rs: WrappedResultSet): AccountStoreTypeRepository = new AccountStoreTypeRepository(
    id = rs.get(astr.id),
    name = rs.get(astr.name),
    description = rs.get(astr.description)
  )

  val astr = AccountStoreTypeRepository.syntax("astr")

  override val autoSession = AutoSession

  def find(id: Long)(implicit session: DBSession = autoSession): Option[AccountStoreTypeRepository] = {
    withSQL {
      select.from(AccountStoreTypeRepository as astr).where.eq(astr.id, id)
    }.map(AccountStoreTypeRepository(astr.resultName)).single.apply()
  }

  def findAll()(implicit session: DBSession = autoSession): List[AccountStoreTypeRepository] = {
    withSQL(select.from(AccountStoreTypeRepository as astr)).map(AccountStoreTypeRepository(astr.resultName)).list.apply()
  }

  def countAll()(implicit session: DBSession = autoSession): Long = {
    withSQL(select(sqls.count).from(AccountStoreTypeRepository as astr)).map(rs => rs.long(1)).single.apply().get
  }

  def findBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Option[AccountStoreTypeRepository] = {
    withSQL {
      select.from(AccountStoreTypeRepository as astr).where.append(where)
    }.map(AccountStoreTypeRepository(astr.resultName)).single.apply()
  }

  def findAllBy(where: SQLSyntax)(implicit session: DBSession = autoSession): List[AccountStoreTypeRepository] = {
    withSQL {
      select.from(AccountStoreTypeRepository as astr).where.append(where)
    }.map(AccountStoreTypeRepository(astr.resultName)).list.apply()
  }

  def countBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Long = {
    withSQL {
      select(sqls.count).from(AccountStoreTypeRepository as astr).where.append(where)
    }.map(_.long(1)).single.apply().get
  }

  def create(
    name: String,
    description: Option[String] = None)(implicit session: DBSession = autoSession): AccountStoreTypeRepository = {
    val generatedKey = withSQL {
      insert.into(AccountStoreTypeRepository).columns(
        column.name,
        column.description
      ).values(
        name,
        description
      )
    }.updateAndReturnGeneratedKey.apply()

    AccountStoreTypeRepository(
      id = generatedKey,
      name = name,
      description = description)
  }

  def batchInsert(entities: Seq[AccountStoreTypeRepository])(implicit session: DBSession = autoSession): Seq[Int] = {
    val params: Seq[Seq[(Symbol, Any)]] = entities.map(entity => 
      Seq(
        'name -> entity.name,
        'description -> entity.description))
        SQL("""insert into account_store_type(
        name,
        description
      ) values (
        {name},
        {description}
      )""").batchByName(params: _*).apply()
    }

  def save(entity: AccountStoreTypeRepository)(implicit session: DBSession = autoSession): AccountStoreTypeRepository = {
    withSQL {
      update(AccountStoreTypeRepository).set(
        column.id -> entity.id,
        column.name -> entity.name,
        column.description -> entity.description
      ).where.eq(column.id, entity.id)
    }.update.apply()
    entity
  }

  def destroy(entity: AccountStoreTypeRepository)(implicit session: DBSession = autoSession): Unit = {
    withSQL { delete.from(AccountStoreTypeRepository).where.eq(column.id, entity.id) }.update.apply()
  }

}
