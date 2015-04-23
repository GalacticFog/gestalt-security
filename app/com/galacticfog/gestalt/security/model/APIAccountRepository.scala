package com.galacticfog.gestalt.security.model

import scalikejdbc._

case class APIAccountRepository(
  apiKey: String, 
  apiSecret: String, 
  defaultOrg: String) {

  def save()(implicit session: DBSession = APIAccountRepository.autoSession): APIAccountRepository = APIAccountRepository.save(this)(session)

  def destroy()(implicit session: DBSession = APIAccountRepository.autoSession): Unit = APIAccountRepository.destroy(this)(session)

}
      

object APIAccountRepository extends SQLSyntaxSupport[APIAccountRepository] {

  override val schemaName = Some("public")

  override val tableName = "api_account"

  override val columns = Seq("api_key", "api_secret", "default_org")

  def apply(aa: SyntaxProvider[APIAccountRepository])(rs: WrappedResultSet): APIAccountRepository = apply(aa.resultName)(rs)
  def apply(aa: ResultName[APIAccountRepository])(rs: WrappedResultSet): APIAccountRepository = new APIAccountRepository(
    apiKey = rs.get(aa.apiKey),
    apiSecret = rs.get(aa.apiSecret),
    defaultOrg = rs.get(aa.defaultOrg)
  )
      
  val aa = APIAccountRepository.syntax("aa")

  override val autoSession = AutoSession

  def find(apiKey: String)(implicit session: DBSession = autoSession): Option[APIAccountRepository] = {
    withSQL {
      select.from(APIAccountRepository as aa).where.eq(aa.apiKey, apiKey)
    }.map(APIAccountRepository(aa.resultName)).single.apply()
  }
          
  def findAll()(implicit session: DBSession = autoSession): List[APIAccountRepository] = {
    withSQL(select.from(APIAccountRepository as aa)).map(APIAccountRepository(aa.resultName)).list.apply()
  }
          
  def countAll()(implicit session: DBSession = autoSession): Long = {
    withSQL(select(sqls"count(1)").from(APIAccountRepository as aa)).map(rs => rs.long(1)).single.apply().get
  }
          
  def findBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Option[APIAccountRepository] = {
    withSQL {
      select.from(APIAccountRepository as aa).where.append(sqls"${where}")
    }.map(APIAccountRepository(aa.resultName)).single.apply()
  }
      
  def findAllBy(where: SQLSyntax)(implicit session: DBSession = autoSession): List[APIAccountRepository] = {
    withSQL {
      select.from(APIAccountRepository as aa).where.append(sqls"${where}")
    }.map(APIAccountRepository(aa.resultName)).list.apply()
  }
      
  def countBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Long = {
    withSQL {
      select(sqls"count(1)").from(APIAccountRepository as aa).where.append(sqls"${where}")
    }.map(_.long(1)).single.apply().get
  }
      
  def create(
    apiKey: String,
    apiSecret: String,
    defaultOrg: String)(implicit session: DBSession = autoSession): APIAccountRepository = {
    withSQL {
      insert.into(APIAccountRepository).columns(
        column.apiKey,
        column.apiSecret,
        column.defaultOrg
      ).values(
        apiKey,
        apiSecret,
        defaultOrg
      )
    }.update.apply()

    APIAccountRepository(
      apiKey = apiKey,
      apiSecret = apiSecret,
      defaultOrg = defaultOrg)
  }

  def save(entity: APIAccountRepository)(implicit session: DBSession = autoSession): APIAccountRepository = {
    withSQL {
      update(APIAccountRepository).set(
        column.apiKey -> entity.apiKey,
        column.apiSecret -> entity.apiSecret,
        column.defaultOrg -> entity.defaultOrg
      ).where.eq(column.apiKey, entity.apiKey)
    }.update.apply()
    entity
  }
        
  def destroy(entity: APIAccountRepository)(implicit session: DBSession = autoSession): Unit = {
    withSQL { delete.from(APIAccountRepository).where.eq(column.apiKey, entity.apiKey) }.update.apply()
  }
        
}
