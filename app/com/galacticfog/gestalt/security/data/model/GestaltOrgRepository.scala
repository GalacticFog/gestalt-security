package com.galacticfog.gestalt.security.data.model

import scalikejdbc._

case class GestaltOrgRepository(
  id: Any,
  name: String,
  fqon: String,
  parent: Option[Any] = None) {

  def save()(implicit session: DBSession = GestaltOrgRepository.autoSession): GestaltOrgRepository = GestaltOrgRepository.save(this)(session)

  def destroy()(implicit session: DBSession = GestaltOrgRepository.autoSession): Unit = GestaltOrgRepository.destroy(this)(session)

}


object GestaltOrgRepository extends SQLSyntaxSupport[GestaltOrgRepository] {

  override val schemaName = Some("public")

  override val tableName = "org"

  override val columns = Seq("id", "name", "fqon", "parent")

  def apply(gor: SyntaxProvider[GestaltOrgRepository])(rs: WrappedResultSet): GestaltOrgRepository = apply(gor.resultName)(rs)
  def apply(gor: ResultName[GestaltOrgRepository])(rs: WrappedResultSet): GestaltOrgRepository = new GestaltOrgRepository(
    id = rs.any(gor.id),
    name = rs.get(gor.name),
    fqon = rs.get(gor.fqon),
    parent = rs.anyOpt(gor.parent)
  )

  val gor = GestaltOrgRepository.syntax("gor")

  override val autoSession = AutoSession

  def find(id: Any)(implicit session: DBSession = autoSession): Option[GestaltOrgRepository] = {
    withSQL {
      select.from(GestaltOrgRepository as gor).where.eq(gor.id, id)
    }.map(GestaltOrgRepository(gor.resultName)).single.apply()
  }

  def findAll()(implicit session: DBSession = autoSession): List[GestaltOrgRepository] = {
    withSQL(select.from(GestaltOrgRepository as gor)).map(GestaltOrgRepository(gor.resultName)).list.apply()
  }

  def countAll()(implicit session: DBSession = autoSession): Long = {
    withSQL(select(sqls.count).from(GestaltOrgRepository as gor)).map(rs => rs.long(1)).single.apply().get
  }

  def findBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Option[GestaltOrgRepository] = {
    withSQL {
      select.from(GestaltOrgRepository as gor).where.append(where)
    }.map(GestaltOrgRepository(gor.resultName)).single.apply()
  }

  def findAllBy(where: SQLSyntax)(implicit session: DBSession = autoSession): List[GestaltOrgRepository] = {
    withSQL {
      select.from(GestaltOrgRepository as gor).where.append(where)
    }.map(GestaltOrgRepository(gor.resultName)).list.apply()
  }

  def countBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Long = {
    withSQL {
      select(sqls.count).from(GestaltOrgRepository as gor).where.append(where)
    }.map(_.long(1)).single.apply().get
  }

  def create(
    id: Any,
    name: String,
    fqon: String,
    parent: Option[Any] = None)(implicit session: DBSession = autoSession): GestaltOrgRepository = {
    withSQL {
      insert.into(GestaltOrgRepository).columns(
        column.id,
        column.name,
        column.fqon,
        column.parent
      ).values(
        id,
        name,
        fqon,
        parent
      )
    }.update.apply()

    GestaltOrgRepository(
      id = id,
      name = name,
      fqon = fqon,
      parent = parent)
  }

  def batchInsert(entities: Seq[GestaltOrgRepository])(implicit session: DBSession = autoSession): Seq[Int] = {
    val params: Seq[Seq[(Symbol, Any)]] = entities.map(entity => 
      Seq(
        'id -> entity.id,
        'name -> entity.name,
        'fqon -> entity.fqon,
        'parent -> entity.parent))
        SQL("""insert into org(
        id,
        name,
        fqon,
        parent
      ) values (
        {id},
        {name},
        {fqon},
        {parent}
      )""").batchByName(params: _*).apply()
    }

  def save(entity: GestaltOrgRepository)(implicit session: DBSession = autoSession): GestaltOrgRepository = {
    withSQL {
      update(GestaltOrgRepository).set(
        column.id -> entity.id,
        column.name -> entity.name,
        column.fqon -> entity.fqon,
        column.parent -> entity.parent
      ).where.eq(column.id, entity.id)
    }.update.apply()
    entity
  }

  def destroy(entity: GestaltOrgRepository)(implicit session: DBSession = autoSession): Unit = {
    withSQL { delete.from(GestaltOrgRepository).where.eq(column.id, entity.id) }.update.apply()
  }

}
