package com.galacticfog.gestalt.security.data.model

import scalikejdbc._

case class AppAccountAssignmentRepository(
  appId: String, 
  accountId: String) {

  def save()(implicit session: DBSession = AppAccountAssignmentRepository.autoSession): AppAccountAssignmentRepository = AppAccountAssignmentRepository.save(this)(session)

  def destroy()(implicit session: DBSession = AppAccountAssignmentRepository.autoSession): Unit = AppAccountAssignmentRepository.destroy(this)(session)

}
      

object AppAccountAssignmentRepository extends SQLSyntaxSupport[AppAccountAssignmentRepository] {

  override val schemaName = Some("public")

  override val tableName = "app_x_account"

  override val columns = Seq("app_id", "account_id")

  def apply(aaar: SyntaxProvider[AppAccountAssignmentRepository])(rs: WrappedResultSet): AppAccountAssignmentRepository = apply(aaar.resultName)(rs)
  def apply(aaar: ResultName[AppAccountAssignmentRepository])(rs: WrappedResultSet): AppAccountAssignmentRepository = new AppAccountAssignmentRepository(
    appId = rs.get(aaar.appId),
    accountId = rs.get(aaar.accountId)
  )
      
  val aaar = AppAccountAssignmentRepository.syntax("aaar")

  override val autoSession = AutoSession

  def find(appId: String, accountId: String)(implicit session: DBSession = autoSession): Option[AppAccountAssignmentRepository] = {
    withSQL {
      select.from(AppAccountAssignmentRepository as aaar).where.eq(aaar.appId, appId).and.eq(aaar.accountId, accountId)
    }.map(AppAccountAssignmentRepository(aaar.resultName)).single.apply()
  }
          
  def findAll()(implicit session: DBSession = autoSession): List[AppAccountAssignmentRepository] = {
    withSQL(select.from(AppAccountAssignmentRepository as aaar)).map(AppAccountAssignmentRepository(aaar.resultName)).list.apply()
  }
          
  def countAll()(implicit session: DBSession = autoSession): Long = {
    withSQL(select(sqls"count(1)").from(AppAccountAssignmentRepository as aaar)).map(rs => rs.long(1)).single.apply().get
  }
          
  def findBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Option[AppAccountAssignmentRepository] = {
    withSQL {
      select.from(AppAccountAssignmentRepository as aaar).where.append(sqls"${where}")
    }.map(AppAccountAssignmentRepository(aaar.resultName)).single.apply()
  }
      
  def findAllBy(where: SQLSyntax)(implicit session: DBSession = autoSession): List[AppAccountAssignmentRepository] = {
    withSQL {
      select.from(AppAccountAssignmentRepository as aaar).where.append(sqls"${where}")
    }.map(AppAccountAssignmentRepository(aaar.resultName)).list.apply()
  }
      
  def countBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Long = {
    withSQL {
      select(sqls"count(1)").from(AppAccountAssignmentRepository as aaar).where.append(sqls"${where}")
    }.map(_.long(1)).single.apply().get
  }
      
  def create(
    appId: String,
    accountId: String)(implicit session: DBSession = autoSession): AppAccountAssignmentRepository = {
    withSQL {
      insert.into(AppAccountAssignmentRepository).columns(
        column.appId,
        column.accountId
      ).values(
        appId,
        accountId
      )
    }.update.apply()

    AppAccountAssignmentRepository(
      appId = appId,
      accountId = accountId)
  }

  def save(entity: AppAccountAssignmentRepository)(implicit session: DBSession = autoSession): AppAccountAssignmentRepository = {
    withSQL {
      update(AppAccountAssignmentRepository).set(
        column.appId -> entity.appId,
        column.accountId -> entity.accountId
      ).where.eq(column.appId, entity.appId).and.eq(column.accountId, entity.accountId)
    }.update.apply()
    entity
  }
        
  def destroy(entity: AppAccountAssignmentRepository)(implicit session: DBSession = autoSession): Unit = {
    withSQL { delete.from(AppAccountAssignmentRepository).where.eq(column.appId, entity.appId).and.eq(column.accountId, entity.accountId) }.update.apply()
  }
        
}
