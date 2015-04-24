package com.galacticfog.gestalt.security.data.model

import scalikejdbc._

case class OrgRepository(
  orgId: String) {

  def save()(implicit session: DBSession = OrgRepository.autoSession): OrgRepository = OrgRepository.save(this)(session)

  def destroy()(implicit session: DBSession = OrgRepository.autoSession): Unit = OrgRepository.destroy(this)(session)

}
      

object OrgRepository extends SQLSyntaxSupport[OrgRepository] {

  override val schemaName = Some("public")

  override val tableName = "org"

  override val columns = Seq("org_id")

  def apply(o: SyntaxProvider[OrgRepository])(rs: WrappedResultSet): OrgRepository = apply(o.resultName)(rs)
  def apply(o: ResultName[OrgRepository])(rs: WrappedResultSet): OrgRepository = new OrgRepository(
    orgId = rs.get(o.orgId)
  )
      
  val o = OrgRepository.syntax("o")

  override val autoSession = AutoSession

  def find(orgId: String)(implicit session: DBSession = autoSession): Option[OrgRepository] = {
    withSQL {
      select.from(OrgRepository as o).where.eq(o.orgId, orgId)
    }.map(OrgRepository(o.resultName)).single.apply()
  }
          
  def findAll()(implicit session: DBSession = autoSession): List[OrgRepository] = {
    withSQL(select.from(OrgRepository as o)).map(OrgRepository(o.resultName)).list.apply()
  }
          
  def countAll()(implicit session: DBSession = autoSession): Long = {
    withSQL(select(sqls"count(1)").from(OrgRepository as o)).map(rs => rs.long(1)).single.apply().get
  }
          
  def findBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Option[OrgRepository] = {
    withSQL {
      select.from(OrgRepository as o).where.append(sqls"${where}")
    }.map(OrgRepository(o.resultName)).single.apply()
  }
      
  def findAllBy(where: SQLSyntax)(implicit session: DBSession = autoSession): List[OrgRepository] = {
    withSQL {
      select.from(OrgRepository as o).where.append(sqls"${where}")
    }.map(OrgRepository(o.resultName)).list.apply()
  }
      
  def countBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Long = {
    withSQL {
      select(sqls"count(1)").from(OrgRepository as o).where.append(sqls"${where}")
    }.map(_.long(1)).single.apply().get
  }
      
  def create(
    orgId: String)(implicit session: DBSession = autoSession): OrgRepository = {
    withSQL {
      insert.into(OrgRepository).columns(
        column.orgId
      ).values(
        orgId
      )
    }.update.apply()

    OrgRepository(
      orgId = orgId)
  }

  def save(entity: OrgRepository)(implicit session: DBSession = autoSession): OrgRepository = {
    withSQL {
      update(OrgRepository).set(
        column.orgId -> entity.orgId
      ).where.eq(column.orgId, entity.orgId)
    }.update.apply()
    entity
  }
        
  def destroy(entity: OrgRepository)(implicit session: DBSession = autoSession): Unit = {
    withSQL { delete.from(OrgRepository).where.eq(column.orgId, entity.orgId) }.update.apply()
  }
        
}
