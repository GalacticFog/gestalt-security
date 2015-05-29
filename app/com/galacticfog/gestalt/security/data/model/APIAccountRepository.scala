package com.galacticfog.gestalt.security.data.model

import scalikejdbc._

case class APIAccountRepository(
  apiKey: String, 
  apiSecret: String, 
  defaultOrg: String, 
  accountId: String) {

  def save()(implicit session: DBSession = APIAccountRepository.autoSession): APIAccountRepository = APIAccountRepository.save(this)(session)

  def destroy()(implicit session: DBSession = APIAccountRepository.autoSession): Unit = APIAccountRepository.destroy(this)(session)

}
      

object APIAccountRepository extends SQLSyntaxSupport[APIAccountRepository] {

  override val schemaName = Some("public")

  override val tableName = "api_account"

  override val columns = Seq("api_key", "api_secret", "default_org", "account_id")

  def apply(apiar: SyntaxProvider[APIAccountRepository])(rs: WrappedResultSet): APIAccountRepository = apply(apiar.resultName)(rs)
  def apply(apiar: ResultName[APIAccountRepository])(rs: WrappedResultSet): APIAccountRepository = new APIAccountRepository(
    apiKey = rs.get(apiar.apiKey),
    apiSecret = rs.get(apiar.apiSecret),
    defaultOrg = rs.get(apiar.defaultOrg),
    accountId = rs.get(apiar.accountId)
  )
      
  val apiar = APIAccountRepository.syntax("apiar")

  override val autoSession = AutoSession

  def find(apiKey: String)(implicit session: DBSession = autoSession): Option[APIAccountRepository] = {
    withSQL {
      select.from(APIAccountRepository as apiar).where.eq(apiar.apiKey, apiKey)
    }.map(APIAccountRepository(apiar.resultName)).single.apply()
  }
          
  def findAll()(implicit session: DBSession = autoSession): List[APIAccountRepository] = {
    withSQL(select.from(APIAccountRepository as apiar)).map(APIAccountRepository(apiar.resultName)).list.apply()
  }
          
  def countAll()(implicit session: DBSession = autoSession): Long = {
    withSQL(select(sqls"count(1)").from(APIAccountRepository as apiar)).map(rs => rs.long(1)).single.apply().get
  }
          
  def findBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Option[APIAccountRepository] = {
    withSQL {
      select.from(APIAccountRepository as apiar).where.append(sqls"${where}")
    }.map(APIAccountRepository(apiar.resultName)).single.apply()
  }
      
  def findAllBy(where: SQLSyntax)(implicit session: DBSession = autoSession): List[APIAccountRepository] = {
    withSQL {
      select.from(APIAccountRepository as apiar).where.append(sqls"${where}")
    }.map(APIAccountRepository(apiar.resultName)).list.apply()
  }
      
  def countBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Long = {
    withSQL {
      select(sqls"count(1)").from(APIAccountRepository as apiar).where.append(sqls"${where}")
    }.map(_.long(1)).single.apply().get
  }
      
  def create(
    apiKey: String,
    apiSecret: String,
    defaultOrg: String,
    accountId: String)(implicit session: DBSession = autoSession): APIAccountRepository = {
    withSQL {
      insert.into(APIAccountRepository).columns(
        column.apiKey,
        column.apiSecret,
        column.defaultOrg,
        column.accountId
      ).values(
        apiKey,
        apiSecret,
        defaultOrg,
        accountId
      )
    }.update.apply()

    APIAccountRepository(
      apiKey = apiKey,
      apiSecret = apiSecret,
      defaultOrg = defaultOrg,
      accountId = accountId)
  }

  def save(entity: APIAccountRepository)(implicit session: DBSession = autoSession): APIAccountRepository = {
    withSQL {
      update(APIAccountRepository).set(
        column.apiKey -> entity.apiKey,
        column.apiSecret -> entity.apiSecret,
        column.defaultOrg -> entity.defaultOrg,
        column.accountId -> entity.accountId
      ).where.eq(column.apiKey, entity.apiKey)
    }.update.apply()
    entity
  }
        
  def destroy(entity: APIAccountRepository)(implicit session: DBSession = autoSession): Unit = {
    withSQL { delete.from(APIAccountRepository).where.eq(column.apiKey, entity.apiKey) }.update.apply()
  }
        
}
