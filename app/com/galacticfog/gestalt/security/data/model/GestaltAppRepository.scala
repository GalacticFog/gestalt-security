package com.galacticfog.gestalt.security.data.model

import scalikejdbc._

case class GestaltAppRepository(
  id: Any,
  name: String,
  orgId: Any,
  serviceOrgId: Option[Any] = None,
  description: Option[String] = None) {

  def save()(implicit session: DBSession = GestaltAppRepository.autoSession): GestaltAppRepository = GestaltAppRepository.save(this)(session)

  def destroy()(implicit session: DBSession = GestaltAppRepository.autoSession): Unit = GestaltAppRepository.destroy(this)(session)

}


object GestaltAppRepository extends SQLSyntaxSupport[GestaltAppRepository] {

  override val schemaName = Some("public")

  override val tableName = "app"

  override val columns = Seq("id", "name", "org_id", "service_org_id", "description")

  def apply(gar: SyntaxProvider[GestaltAppRepository])(rs: WrappedResultSet): GestaltAppRepository = apply(gar.resultName)(rs)
  def apply(gar: ResultName[GestaltAppRepository])(rs: WrappedResultSet): GestaltAppRepository = new GestaltAppRepository(
    id = rs.any(gar.id),
    name = rs.get(gar.name),
    orgId = rs.any(gar.orgId),
    serviceOrgId = rs.anyOpt(gar.serviceOrgId),
    description = rs.get(gar.description)
  )

  val gar = GestaltAppRepository.syntax("gar")

  override val autoSession = AutoSession

  def find(id: Any)(implicit session: DBSession = autoSession): Option[GestaltAppRepository] = {
    withSQL {
      select.from(GestaltAppRepository as gar).where.eq(gar.id, id)
    }.map(GestaltAppRepository(gar.resultName)).single.apply()
  }

  def findAll()(implicit session: DBSession = autoSession): List[GestaltAppRepository] = {
    withSQL(select.from(GestaltAppRepository as gar)).map(GestaltAppRepository(gar.resultName)).list.apply()
  }

  def countAll()(implicit session: DBSession = autoSession): Long = {
    withSQL(select(sqls.count).from(GestaltAppRepository as gar)).map(rs => rs.long(1)).single.apply().get
  }

  def findBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Option[GestaltAppRepository] = {
    withSQL {
      select.from(GestaltAppRepository as gar).where.append(where)
    }.map(GestaltAppRepository(gar.resultName)).single.apply()
  }

  def findAllBy(where: SQLSyntax)(implicit session: DBSession = autoSession): List[GestaltAppRepository] = {
    withSQL {
      select.from(GestaltAppRepository as gar).where.append(where)
    }.map(GestaltAppRepository(gar.resultName)).list.apply()
  }

  def countBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Long = {
    withSQL {
      select(sqls.count).from(GestaltAppRepository as gar).where.append(where)
    }.map(_.long(1)).single.apply().get
  }

  def create(
    id: Any,
    name: String,
    orgId: Any,
    serviceOrgId: Option[Any] = None,
    description: Option[String] = None)(implicit session: DBSession = autoSession): GestaltAppRepository = {
    withSQL {
      insert.into(GestaltAppRepository).columns(
        column.id,
        column.name,
        column.orgId,
        column.serviceOrgId,
        column.description
      ).values(
        id,
        name,
        orgId,
        serviceOrgId,
        description
      )
    }.update.apply()

    GestaltAppRepository(
      id = id,
      name = name,
      orgId = orgId,
      serviceOrgId = serviceOrgId,
      description = description)
  }

  def batchInsert(entities: Seq[GestaltAppRepository])(implicit session: DBSession = autoSession): Seq[Int] = {
    val params: Seq[Seq[(Symbol, Any)]] = entities.map(entity => 
      Seq(
        'id -> entity.id,
        'name -> entity.name,
        'orgId -> entity.orgId,
        'serviceOrgId -> entity.serviceOrgId,
        'description -> entity.description))
        SQL("""insert into app(
        id,
        name,
        org_id,
        service_org_id,
        description
      ) values (
        {id},
        {name},
        {orgId},
        {serviceOrgId},
        {description}
      )""").batchByName(params: _*).apply()
    }

  def save(entity: GestaltAppRepository)(implicit session: DBSession = autoSession): GestaltAppRepository = {
    withSQL {
      update(GestaltAppRepository).set(
        column.id -> entity.id,
        column.name -> entity.name,
        column.orgId -> entity.orgId,
        column.serviceOrgId -> entity.serviceOrgId,
        column.description -> entity.description
      ).where.eq(column.id, entity.id)
    }.update.apply()
    entity
  }

  def destroy(entity: GestaltAppRepository)(implicit session: DBSession = autoSession): Unit = {
    withSQL { delete.from(GestaltAppRepository).where.eq(column.id, entity.id) }.update.apply()
  }

}
