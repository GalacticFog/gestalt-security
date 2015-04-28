package com.galacticfog.gestalt.security.data.model

import scalikejdbc._

case class AppUserStoreRepository(
  appId: String, 
  groupId: String) {

  def save()(implicit session: DBSession = AppUserStoreRepository.autoSession): AppUserStoreRepository = AppUserStoreRepository.save(this)(session)

  def destroy()(implicit session: DBSession = AppUserStoreRepository.autoSession): Unit = AppUserStoreRepository.destroy(this)(session)

}
      

object AppUserStoreRepository extends SQLSyntaxSupport[AppUserStoreRepository] {

  override val schemaName = Some("public")

  override val tableName = "app_user_store"

  override val columns = Seq("app_id", "group_id")

  def apply(ausr: SyntaxProvider[AppUserStoreRepository])(rs: WrappedResultSet): AppUserStoreRepository = apply(ausr.resultName)(rs)
  def apply(ausr: ResultName[AppUserStoreRepository])(rs: WrappedResultSet): AppUserStoreRepository = new AppUserStoreRepository(
    appId = rs.get(ausr.appId),
    groupId = rs.get(ausr.groupId)
  )
      
  val ausr = AppUserStoreRepository.syntax("ausr")

  override val autoSession = AutoSession

  def find(appId: String, groupId: String)(implicit session: DBSession = autoSession): Option[AppUserStoreRepository] = {
    withSQL {
      select.from(AppUserStoreRepository as ausr).where.eq(ausr.appId, appId).and.eq(ausr.groupId, groupId)
    }.map(AppUserStoreRepository(ausr.resultName)).single.apply()
  }
          
  def findAll()(implicit session: DBSession = autoSession): List[AppUserStoreRepository] = {
    withSQL(select.from(AppUserStoreRepository as ausr)).map(AppUserStoreRepository(ausr.resultName)).list.apply()
  }
          
  def countAll()(implicit session: DBSession = autoSession): Long = {
    withSQL(select(sqls"count(1)").from(AppUserStoreRepository as ausr)).map(rs => rs.long(1)).single.apply().get
  }
          
  def findBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Option[AppUserStoreRepository] = {
    withSQL {
      select.from(AppUserStoreRepository as ausr).where.append(sqls"${where}")
    }.map(AppUserStoreRepository(ausr.resultName)).single.apply()
  }
      
  def findAllBy(where: SQLSyntax)(implicit session: DBSession = autoSession): List[AppUserStoreRepository] = {
    withSQL {
      select.from(AppUserStoreRepository as ausr).where.append(sqls"${where}")
    }.map(AppUserStoreRepository(ausr.resultName)).list.apply()
  }
      
  def countBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Long = {
    withSQL {
      select(sqls"count(1)").from(AppUserStoreRepository as ausr).where.append(sqls"${where}")
    }.map(_.long(1)).single.apply().get
  }
      
  def create(
    appId: String,
    groupId: String)(implicit session: DBSession = autoSession): AppUserStoreRepository = {
    withSQL {
      insert.into(AppUserStoreRepository).columns(
        column.appId,
        column.groupId
      ).values(
        appId,
        groupId
      )
    }.update.apply()

    AppUserStoreRepository(
      appId = appId,
      groupId = groupId)
  }

  def save(entity: AppUserStoreRepository)(implicit session: DBSession = autoSession): AppUserStoreRepository = {
    withSQL {
      update(AppUserStoreRepository).set(
        column.appId -> entity.appId,
        column.groupId -> entity.groupId
      ).where.eq(column.appId, entity.appId).and.eq(column.groupId, entity.groupId)
    }.update.apply()
    entity
  }
        
  def destroy(entity: AppUserStoreRepository)(implicit session: DBSession = autoSession): Unit = {
    withSQL { delete.from(AppUserStoreRepository).where.eq(column.appId, entity.appId).and.eq(column.groupId, entity.groupId) }.update.apply()
  }
        
}
