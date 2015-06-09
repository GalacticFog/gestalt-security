package com.galacticfog.gestalt.security.data.model

import scalikejdbc._

case class AppGroupAssignmentRepository(
  appId: String, 
  groupId: String) {

  def save()(implicit session: DBSession = AppGroupAssignmentRepository.autoSession): AppGroupAssignmentRepository = AppGroupAssignmentRepository.save(this)(session)

  def destroy()(implicit session: DBSession = AppGroupAssignmentRepository.autoSession): Unit = AppGroupAssignmentRepository.destroy(this)(session)

}
      

object AppGroupAssignmentRepository extends SQLSyntaxSupport[AppGroupAssignmentRepository] {

  override val schemaName = Some("public")

  override val tableName = "app_x_group"

  override val columns = Seq("app_id", "group_id")

  def apply(agar: SyntaxProvider[AppGroupAssignmentRepository])(rs: WrappedResultSet): AppGroupAssignmentRepository = apply(agar.resultName)(rs)
  def apply(agar: ResultName[AppGroupAssignmentRepository])(rs: WrappedResultSet): AppGroupAssignmentRepository = new AppGroupAssignmentRepository(
    appId = rs.get(agar.appId),
    groupId = rs.get(agar.groupId)
  )
      
  val agar = AppGroupAssignmentRepository.syntax("agar")

  override val autoSession = AutoSession

  def find(appId: String, groupId: String)(implicit session: DBSession = autoSession): Option[AppGroupAssignmentRepository] = {
    withSQL {
      select.from(AppGroupAssignmentRepository as agar).where.eq(agar.appId, appId).and.eq(agar.groupId, groupId)
    }.map(AppGroupAssignmentRepository(agar.resultName)).single.apply()
  }
          
  def findAll()(implicit session: DBSession = autoSession): List[AppGroupAssignmentRepository] = {
    withSQL(select.from(AppGroupAssignmentRepository as agar)).map(AppGroupAssignmentRepository(agar.resultName)).list.apply()
  }
          
  def countAll()(implicit session: DBSession = autoSession): Long = {
    withSQL(select(sqls"count(1)").from(AppGroupAssignmentRepository as agar)).map(rs => rs.long(1)).single.apply().get
  }
          
  def findBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Option[AppGroupAssignmentRepository] = {
    withSQL {
      select.from(AppGroupAssignmentRepository as agar).where.append(sqls"${where}")
    }.map(AppGroupAssignmentRepository(agar.resultName)).single.apply()
  }
      
  def findAllBy(where: SQLSyntax)(implicit session: DBSession = autoSession): List[AppGroupAssignmentRepository] = {
    withSQL {
      select.from(AppGroupAssignmentRepository as agar).where.append(sqls"${where}")
    }.map(AppGroupAssignmentRepository(agar.resultName)).list.apply()
  }
      
  def countBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Long = {
    withSQL {
      select(sqls"count(1)").from(AppGroupAssignmentRepository as agar).where.append(sqls"${where}")
    }.map(_.long(1)).single.apply().get
  }
      
  def create(
    appId: String,
    groupId: String)(implicit session: DBSession = autoSession): AppGroupAssignmentRepository = {
    withSQL {
      insert.into(AppGroupAssignmentRepository).columns(
        column.appId,
        column.groupId
      ).values(
        appId,
        groupId
      )
    }.update.apply()

    AppGroupAssignmentRepository(
      appId = appId,
      groupId = groupId)
  }

  def save(entity: AppGroupAssignmentRepository)(implicit session: DBSession = autoSession): AppGroupAssignmentRepository = {
    withSQL {
      update(AppGroupAssignmentRepository).set(
        column.appId -> entity.appId,
        column.groupId -> entity.groupId
      ).where.eq(column.appId, entity.appId).and.eq(column.groupId, entity.groupId)
    }.update.apply()
    entity
  }
        
  def destroy(entity: AppGroupAssignmentRepository)(implicit session: DBSession = autoSession): Unit = {
    withSQL { delete.from(AppGroupAssignmentRepository).where.eq(column.appId, entity.appId).and.eq(column.groupId, entity.groupId) }.update.apply()
  }
        
}
