package com.galacticfog.gestalt.security.data.model

import scalikejdbc._

case class GestaltDirectoryTypeRepository(
  id: Long,
  name: String,
  description: Option[String] = None) {

  def save()(implicit session: DBSession = GestaltDirectoryTypeRepository.autoSession): GestaltDirectoryTypeRepository = GestaltDirectoryTypeRepository.save(this)(session)

  def destroy()(implicit session: DBSession = GestaltDirectoryTypeRepository.autoSession): Unit = GestaltDirectoryTypeRepository.destroy(this)(session)

}


object GestaltDirectoryTypeRepository extends SQLSyntaxSupport[GestaltDirectoryTypeRepository] {

  override val schemaName = Some("public")

  override val tableName = "directory_type"

  override val columns = Seq("id", "name", "description")

  def apply(gdtr: SyntaxProvider[GestaltDirectoryTypeRepository])(rs: WrappedResultSet): GestaltDirectoryTypeRepository = apply(gdtr.resultName)(rs)
  def apply(gdtr: ResultName[GestaltDirectoryTypeRepository])(rs: WrappedResultSet): GestaltDirectoryTypeRepository = new GestaltDirectoryTypeRepository(
    id = rs.get(gdtr.id),
    name = rs.get(gdtr.name),
    description = rs.get(gdtr.description)
  )

  val gdtr = GestaltDirectoryTypeRepository.syntax("gdtr")

  override val autoSession = AutoSession

  def find(id: Long)(implicit session: DBSession = autoSession): Option[GestaltDirectoryTypeRepository] = {
    withSQL {
      select.from(GestaltDirectoryTypeRepository as gdtr).where.eq(gdtr.id, id)
    }.map(GestaltDirectoryTypeRepository(gdtr.resultName)).single.apply()
  }

  def findAll()(implicit session: DBSession = autoSession): List[GestaltDirectoryTypeRepository] = {
    withSQL(select.from(GestaltDirectoryTypeRepository as gdtr)).map(GestaltDirectoryTypeRepository(gdtr.resultName)).list.apply()
  }

  def countAll()(implicit session: DBSession = autoSession): Long = {
    withSQL(select(sqls.count).from(GestaltDirectoryTypeRepository as gdtr)).map(rs => rs.long(1)).single.apply().get
  }

  def findBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Option[GestaltDirectoryTypeRepository] = {
    withSQL {
      select.from(GestaltDirectoryTypeRepository as gdtr).where.append(where)
    }.map(GestaltDirectoryTypeRepository(gdtr.resultName)).single.apply()
  }

  def findAllBy(where: SQLSyntax)(implicit session: DBSession = autoSession): List[GestaltDirectoryTypeRepository] = {
    withSQL {
      select.from(GestaltDirectoryTypeRepository as gdtr).where.append(where)
    }.map(GestaltDirectoryTypeRepository(gdtr.resultName)).list.apply()
  }

  def countBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Long = {
    withSQL {
      select(sqls.count).from(GestaltDirectoryTypeRepository as gdtr).where.append(where)
    }.map(_.long(1)).single.apply().get
  }

  def create(
    name: String,
    description: Option[String] = None)(implicit session: DBSession = autoSession): GestaltDirectoryTypeRepository = {
    val generatedKey = withSQL {
      insert.into(GestaltDirectoryTypeRepository).columns(
        column.name,
        column.description
      ).values(
        name,
        description
      )
    }.updateAndReturnGeneratedKey.apply()

    GestaltDirectoryTypeRepository(
      id = generatedKey,
      name = name,
      description = description)
  }

  def batchInsert(entities: Seq[GestaltDirectoryTypeRepository])(implicit session: DBSession = autoSession): Seq[Int] = {
    val params: Seq[Seq[(Symbol, Any)]] = entities.map(entity => 
      Seq(
        'name -> entity.name,
        'description -> entity.description))
        SQL("""insert into directory_type(
        name,
        description
      ) values (
        {name},
        {description}
      )""").batchByName(params: _*).apply()
    }

  def save(entity: GestaltDirectoryTypeRepository)(implicit session: DBSession = autoSession): GestaltDirectoryTypeRepository = {
    withSQL {
      update(GestaltDirectoryTypeRepository).set(
        column.id -> entity.id,
        column.name -> entity.name,
        column.description -> entity.description
      ).where.eq(column.id, entity.id)
    }.update.apply()
    entity
  }

  def destroy(entity: GestaltDirectoryTypeRepository)(implicit session: DBSession = autoSession): Unit = {
    withSQL { delete.from(GestaltDirectoryTypeRepository).where.eq(column.id, entity.id) }.update.apply()
  }

}
