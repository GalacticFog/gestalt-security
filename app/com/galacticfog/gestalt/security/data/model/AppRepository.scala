package com.galacticfog.gestalt.security.data.model

import scalikejdbc._

case class AppRepository(
  appId: String, 
  appName: String, 
  orgId: String) {

  def save()(implicit session: DBSession = AppRepository.autoSession): AppRepository = AppRepository.save(this)(session)

  def destroy()(implicit session: DBSession = AppRepository.autoSession): Unit = AppRepository.destroy(this)(session)

}
      

object AppRepository extends SQLSyntaxSupport[AppRepository] {

  override val schemaName = Some("public")

  override val tableName = "app"

  override val columns = Seq("app_id", "app_name", "org_id")

  def apply(ar: SyntaxProvider[AppRepository])(rs: WrappedResultSet): AppRepository = apply(ar.resultName)(rs)
  def apply(ar: ResultName[AppRepository])(rs: WrappedResultSet): AppRepository = new AppRepository(
    appId = rs.get(ar.appId),
    appName = rs.get(ar.appName),
    orgId = rs.get(ar.orgId)
  )
      
  val ar = AppRepository.syntax("ar")

  override val autoSession = AutoSession

  def find(appId: String)(implicit session: DBSession = autoSession): Option[AppRepository] = {
    withSQL {
      select.from(AppRepository as ar).where.eq(ar.appId, appId)
    }.map(AppRepository(ar.resultName)).single.apply()
  }
          
  def findAll()(implicit session: DBSession = autoSession): List[AppRepository] = {
    withSQL(select.from(AppRepository as ar)).map(AppRepository(ar.resultName)).list.apply()
  }
          
  def countAll()(implicit session: DBSession = autoSession): Long = {
    withSQL(select(sqls"count(1)").from(AppRepository as ar)).map(rs => rs.long(1)).single.apply().get
  }
          
  def findBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Option[AppRepository] = {
    withSQL {
      select.from(AppRepository as ar).where.append(sqls"${where}")
    }.map(AppRepository(ar.resultName)).single.apply()
  }
      
  def findAllBy(where: SQLSyntax)(implicit session: DBSession = autoSession): List[AppRepository] = {
    withSQL {
      select.from(AppRepository as ar).where.append(sqls"${where}")
    }.map(AppRepository(ar.resultName)).list.apply()
  }
      
  def countBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Long = {
    withSQL {
      select(sqls"count(1)").from(AppRepository as ar).where.append(sqls"${where}")
    }.map(_.long(1)).single.apply().get
  }
      
  def create(
    appId: String,
    appName: String,
    orgId: String)(implicit session: DBSession = autoSession): AppRepository = {
    withSQL {
      insert.into(AppRepository).columns(
        column.appId,
        column.appName,
        column.orgId
      ).values(
        appId,
        appName,
        orgId
      )
    }.update.apply()

    AppRepository(
      appId = appId,
      appName = appName,
      orgId = orgId)
  }

  def save(entity: AppRepository)(implicit session: DBSession = autoSession): AppRepository = {
    withSQL {
      update(AppRepository).set(
        column.appId -> entity.appId,
        column.appName -> entity.appName,
        column.orgId -> entity.orgId
      ).where.eq(column.appId, entity.appId)
    }.update.apply()
    entity
  }
        
  def destroy(entity: AppRepository)(implicit session: DBSession = autoSession): Unit = {
    withSQL { delete.from(AppRepository).where.eq(column.appId, entity.appId) }.update.apply()
  }
        
}
