package com.galacticfog.gestalt.security.data.model

import scalikejdbc._

case class GestaltOrgRepository(
  orgId: String, 
  orgName: String) {

  def save()(implicit session: DBSession = GestaltOrgRepository.autoSession): GestaltOrgRepository = GestaltOrgRepository.save(this)(session)

  def destroy()(implicit session: DBSession = GestaltOrgRepository.autoSession): Unit = GestaltOrgRepository.destroy(this)(session)

}
      

object GestaltOrgRepository extends SQLSyntaxSupport[GestaltOrgRepository] {

  override val schemaName = Some("public")

  override val tableName = "org"

  override val columns = Seq("org_id", "org_name")

  def apply(gor: SyntaxProvider[GestaltOrgRepository])(rs: WrappedResultSet): GestaltOrgRepository = apply(gor.resultName)(rs)
  def apply(gor: ResultName[GestaltOrgRepository])(rs: WrappedResultSet): GestaltOrgRepository = new GestaltOrgRepository(
    orgId = rs.get(gor.orgId),
    orgName = rs.get(gor.orgName)
  )
      
  val gor = GestaltOrgRepository.syntax("gor")

  override val autoSession = AutoSession

  def find(orgId: String)(implicit session: DBSession = autoSession): Option[GestaltOrgRepository] = {
    withSQL {
      select.from(GestaltOrgRepository as gor).where.eq(gor.orgId, orgId)
    }.map(GestaltOrgRepository(gor.resultName)).single.apply()
  }
          
  def findAll()(implicit session: DBSession = autoSession): List[GestaltOrgRepository] = {
    withSQL(select.from(GestaltOrgRepository as gor)).map(GestaltOrgRepository(gor.resultName)).list.apply()
  }
          
  def countAll()(implicit session: DBSession = autoSession): Long = {
    withSQL(select(sqls"count(1)").from(GestaltOrgRepository as gor)).map(rs => rs.long(1)).single.apply().get
  }
          
  def findBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Option[GestaltOrgRepository] = {
    withSQL {
      select.from(GestaltOrgRepository as gor).where.append(sqls"${where}")
    }.map(GestaltOrgRepository(gor.resultName)).single.apply()
  }
      
  def findAllBy(where: SQLSyntax)(implicit session: DBSession = autoSession): List[GestaltOrgRepository] = {
    withSQL {
      select.from(GestaltOrgRepository as gor).where.append(sqls"${where}")
    }.map(GestaltOrgRepository(gor.resultName)).list.apply()
  }
      
  def countBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Long = {
    withSQL {
      select(sqls"count(1)").from(GestaltOrgRepository as gor).where.append(sqls"${where}")
    }.map(_.long(1)).single.apply().get
  }
      
  def create(
    orgId: String,
    orgName: String)(implicit session: DBSession = autoSession): GestaltOrgRepository = {
    withSQL {
      insert.into(GestaltOrgRepository).columns(
        column.orgId,
        column.orgName
      ).values(
        orgId,
        orgName
      )
    }.update.apply()

    GestaltOrgRepository(
      orgId = orgId,
      orgName = orgName)
  }

  def save(entity: GestaltOrgRepository)(implicit session: DBSession = autoSession): GestaltOrgRepository = {
    withSQL {
      update(GestaltOrgRepository).set(
        column.orgId -> entity.orgId,
        column.orgName -> entity.orgName
      ).where.eq(column.orgId, entity.orgId)
    }.update.apply()
    entity
  }
        
  def destroy(entity: GestaltOrgRepository)(implicit session: DBSession = autoSession): Unit = {
    withSQL { delete.from(GestaltOrgRepository).where.eq(column.orgId, entity.orgId) }.update.apply()
  }
        
}
