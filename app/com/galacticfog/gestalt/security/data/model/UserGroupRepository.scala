package com.galacticfog.gestalt.security.data.model

import scalikejdbc._

case class UserGroupRepository(
  id: Any,
  dirId: Any,
  name: String,
  disabled: Boolean) {

  def save()(implicit session: DBSession = UserGroupRepository.autoSession): UserGroupRepository = UserGroupRepository.save(this)(session)

  def destroy()(implicit session: DBSession = UserGroupRepository.autoSession): Unit = UserGroupRepository.destroy(this)(session)

}


object UserGroupRepository extends SQLSyntaxSupport[UserGroupRepository] {

  override val schemaName = Some("public")

  override val tableName = "account_group"

  override val columns = Seq("id", "dir_id", "name", "disabled")

  def apply(ugr: SyntaxProvider[UserGroupRepository])(rs: WrappedResultSet): UserGroupRepository = apply(ugr.resultName)(rs)
  def apply(ugr: ResultName[UserGroupRepository])(rs: WrappedResultSet): UserGroupRepository = new UserGroupRepository(
    id = rs.any(ugr.id),
    dirId = rs.any(ugr.dirId),
    name = rs.get(ugr.name),
    disabled = rs.get(ugr.disabled)
  )

  val ugr = UserGroupRepository.syntax("ugr")

  override val autoSession = AutoSession

  def find(id: Any)(implicit session: DBSession = autoSession): Option[UserGroupRepository] = {
    withSQL {
      select.from(UserGroupRepository as ugr).where.eq(ugr.id, id)
    }.map(UserGroupRepository(ugr.resultName)).single.apply()
  }

  def findAll()(implicit session: DBSession = autoSession): List[UserGroupRepository] = {
    withSQL(select.from(UserGroupRepository as ugr)).map(UserGroupRepository(ugr.resultName)).list.apply()
  }

  def countAll()(implicit session: DBSession = autoSession): Long = {
    withSQL(select(sqls.count).from(UserGroupRepository as ugr)).map(rs => rs.long(1)).single.apply().get
  }

  def findBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Option[UserGroupRepository] = {
    withSQL {
      select.from(UserGroupRepository as ugr).where.append(where)
    }.map(UserGroupRepository(ugr.resultName)).single.apply()
  }

  def findAllBy(where: SQLSyntax)(implicit session: DBSession = autoSession): List[UserGroupRepository] = {
    withSQL {
      select.from(UserGroupRepository as ugr).where.append(where)
    }.map(UserGroupRepository(ugr.resultName)).list.apply()
  }

  def countBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Long = {
    withSQL {
      select(sqls.count).from(UserGroupRepository as ugr).where.append(where)
    }.map(_.long(1)).single.apply().get
  }

  def create(
    id: Any,
    dirId: Any,
    name: String,
    disabled: Boolean)(implicit session: DBSession = autoSession): UserGroupRepository = {
    withSQL {
      insert.into(UserGroupRepository).columns(
        column.id,
        column.dirId,
        column.name,
        column.disabled
      ).values(
        id,
        dirId,
        name,
        disabled
      )
    }.update.apply()

    UserGroupRepository(
      id = id,
      dirId = dirId,
      name = name,
      disabled = disabled)
  }

  def batchInsert(entities: Seq[UserGroupRepository])(implicit session: DBSession = autoSession): Seq[Int] = {
    val params: Seq[Seq[(Symbol, Any)]] = entities.map(entity => 
      Seq(
        'id -> entity.id,
        'dirId -> entity.dirId,
        'name -> entity.name,
        'disabled -> entity.disabled))
        SQL("""insert into account_group(
        id,
        dir_id,
        name,
        disabled
      ) values (
        {id},
        {dirId},
        {name},
        {disabled}
      )""").batchByName(params: _*).apply()
    }

  def save(entity: UserGroupRepository)(implicit session: DBSession = autoSession): UserGroupRepository = {
    withSQL {
      update(UserGroupRepository).set(
        column.id -> entity.id,
        column.dirId -> entity.dirId,
        column.name -> entity.name,
        column.disabled -> entity.disabled
      ).where.eq(column.id, entity.id)
    }.update.apply()
    entity
  }

  def destroy(entity: UserGroupRepository)(implicit session: DBSession = autoSession): Unit = {
    withSQL { delete.from(UserGroupRepository).where.eq(column.id, entity.id) }.update.apply()
  }

}
