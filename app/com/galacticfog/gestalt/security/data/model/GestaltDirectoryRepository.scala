package com.galacticfog.gestalt.security.data.model

import scalikejdbc._

case class GestaltDirectoryRepository(
  id: Any,
  name: String,
  description: Option[String] = None,
  orgId: Any,
  config: Option[String] = None,
  directoryType: String) {

  def save()(implicit session: DBSession = GestaltDirectoryRepository.autoSession): GestaltDirectoryRepository = GestaltDirectoryRepository.save(this)(session)

  def destroy()(implicit session: DBSession = GestaltDirectoryRepository.autoSession): Unit = GestaltDirectoryRepository.destroy(this)(session)

}


object GestaltDirectoryRepository extends SQLSyntaxSupport[GestaltDirectoryRepository] {

  override val schemaName = Some("public")

  override val tableName = "directory"

  override val columns = Seq("id", "name", "description", "org_id", "config", "directory_type")

  def apply(gdr: SyntaxProvider[GestaltDirectoryRepository])(rs: WrappedResultSet): GestaltDirectoryRepository = apply(gdr.resultName)(rs)
  def apply(gdr: ResultName[GestaltDirectoryRepository])(rs: WrappedResultSet): GestaltDirectoryRepository = new GestaltDirectoryRepository(
    id = rs.any(gdr.id),
    name = rs.get(gdr.name),
    description = rs.get(gdr.description),
    orgId = rs.any(gdr.orgId),
    config = rs.get(gdr.config),
    directoryType = rs.get(gdr.directoryType)
  )

  val gdr = GestaltDirectoryRepository.syntax("gdr")

  override val autoSession = AutoSession

  def find(id: Any)(implicit session: DBSession = autoSession): Option[GestaltDirectoryRepository] = {
    withSQL {
      select.from(GestaltDirectoryRepository as gdr).where.eq(gdr.id, id)
    }.map(GestaltDirectoryRepository(gdr.resultName)).single.apply()
  }

  def findAll()(implicit session: DBSession = autoSession): List[GestaltDirectoryRepository] = {
    withSQL(select.from(GestaltDirectoryRepository as gdr)).map(GestaltDirectoryRepository(gdr.resultName)).list.apply()
  }

  def countAll()(implicit session: DBSession = autoSession): Long = {
    withSQL(select(sqls.count).from(GestaltDirectoryRepository as gdr)).map(rs => rs.long(1)).single.apply().get
  }

  def findBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Option[GestaltDirectoryRepository] = {
    withSQL {
      select.from(GestaltDirectoryRepository as gdr).where.append(where)
    }.map(GestaltDirectoryRepository(gdr.resultName)).single.apply()
  }

  def findAllBy(where: SQLSyntax)(implicit session: DBSession = autoSession): List[GestaltDirectoryRepository] = {
    withSQL {
      select.from(GestaltDirectoryRepository as gdr).where.append(where)
    }.map(GestaltDirectoryRepository(gdr.resultName)).list.apply()
  }

  def countBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Long = {
    withSQL {
      select(sqls.count).from(GestaltDirectoryRepository as gdr).where.append(where)
    }.map(_.long(1)).single.apply().get
  }

  def create(
    id: Any,
    name: String,
    description: Option[String] = None,
    orgId: Any,
    config: Option[String] = None,
    directoryType: String)(implicit session: DBSession = autoSession): GestaltDirectoryRepository = {
    withSQL {
      insert.into(GestaltDirectoryRepository).columns(
        column.id,
        column.name,
        column.description,
        column.orgId,
        column.config,
        column.directoryType
      ).values(
        id,
        name,
        description,
        orgId,
        config,
        directoryType
      )
    }.update.apply()

    GestaltDirectoryRepository(
      id = id,
      name = name,
      description = description,
      orgId = orgId,
      config = config,
      directoryType = directoryType)
  }

  def batchInsert(entities: Seq[GestaltDirectoryRepository])(implicit session: DBSession = autoSession): Seq[Int] = {
    val params: Seq[Seq[(Symbol, Any)]] = entities.map(entity => 
      Seq(
        'id -> entity.id,
        'name -> entity.name,
        'description -> entity.description,
        'orgId -> entity.orgId,
        'config -> entity.config,
        'directoryType -> entity.directoryType))
        SQL("""insert into directory(
        id,
        name,
        description,
        org_id,
        config,
        directory_type
      ) values (
        {id},
        {name},
        {description},
        {orgId},
        {config},
        {directoryType}
      )""").batchByName(params: _*).apply()
    }

  def save(entity: GestaltDirectoryRepository)(implicit session: DBSession = autoSession): GestaltDirectoryRepository = {
    withSQL {
      update(GestaltDirectoryRepository).set(
        column.id -> entity.id,
        column.name -> entity.name,
        column.description -> entity.description,
        column.orgId -> entity.orgId,
        column.config -> entity.config,
        column.directoryType -> entity.directoryType
      ).where.eq(column.id, entity.id)
    }.update.apply()
    entity
  }

  def destroy(entity: GestaltDirectoryRepository)(implicit session: DBSession = autoSession): Unit = {
    withSQL { delete.from(GestaltDirectoryRepository).where.eq(column.id, entity.id) }.update.apply()
  }

}
