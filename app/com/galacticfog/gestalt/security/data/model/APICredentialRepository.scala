package com.galacticfog.gestalt.security.data.model

import scalikejdbc._

case class APICredentialRepository(
  apiKey: String,
  apiSecret: String,
  accountId: Any,
  orgId: Any,
  disabled: Boolean) {

  def save()(implicit session: DBSession = APICredentialRepository.autoSession): APICredentialRepository = APICredentialRepository.save(this)(session)

  def destroy()(implicit session: DBSession = APICredentialRepository.autoSession): Unit = APICredentialRepository.destroy(this)(session)

}


object APICredentialRepository extends SQLSyntaxSupport[APICredentialRepository] {

  override val schemaName = Some("public")

  override val tableName = "api_credential"

  override val columns = Seq("api_key", "api_secret", "account_id", "org_id", "disabled")

  def apply(apicr: SyntaxProvider[APICredentialRepository])(rs: WrappedResultSet): APICredentialRepository = apply(apicr.resultName)(rs)
  def apply(apicr: ResultName[APICredentialRepository])(rs: WrappedResultSet): APICredentialRepository = new APICredentialRepository(
    apiKey = rs.get(apicr.apiKey),
    apiSecret = rs.get(apicr.apiSecret),
    accountId = rs.any(apicr.accountId),
    orgId = rs.any(apicr.orgId),
    disabled = rs.get(apicr.disabled)
  )

  val apicr = APICredentialRepository.syntax("apicr")

  override val autoSession = AutoSession

  def find(apiKey: String)(implicit session: DBSession = autoSession): Option[APICredentialRepository] = {
    withSQL {
      select.from(APICredentialRepository as apicr).where.eq(apicr.apiKey, apiKey)
    }.map(APICredentialRepository(apicr.resultName)).single.apply()
  }

  def findAll()(implicit session: DBSession = autoSession): List[APICredentialRepository] = {
    withSQL(select.from(APICredentialRepository as apicr)).map(APICredentialRepository(apicr.resultName)).list.apply()
  }

  def countAll()(implicit session: DBSession = autoSession): Long = {
    withSQL(select(sqls.count).from(APICredentialRepository as apicr)).map(rs => rs.long(1)).single.apply().get
  }

  def findBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Option[APICredentialRepository] = {
    withSQL {
      select.from(APICredentialRepository as apicr).where.append(where)
    }.map(APICredentialRepository(apicr.resultName)).single.apply()
  }

  def findAllBy(where: SQLSyntax)(implicit session: DBSession = autoSession): List[APICredentialRepository] = {
    withSQL {
      select.from(APICredentialRepository as apicr).where.append(where)
    }.map(APICredentialRepository(apicr.resultName)).list.apply()
  }

  def countBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Long = {
    withSQL {
      select(sqls.count).from(APICredentialRepository as apicr).where.append(where)
    }.map(_.long(1)).single.apply().get
  }

  def create(
    apiKey: String,
    apiSecret: String,
    accountId: Any,
    orgId: Any,
    disabled: Boolean)(implicit session: DBSession = autoSession): APICredentialRepository = {
    withSQL {
      insert.into(APICredentialRepository).columns(
        column.apiKey,
        column.apiSecret,
        column.accountId,
        column.orgId,
        column.disabled
      ).values(
        apiKey,
        apiSecret,
        accountId,
        orgId,
        disabled
      )
    }.update.apply()

    APICredentialRepository(
      apiKey = apiKey,
      apiSecret = apiSecret,
      accountId = accountId,
      orgId = orgId,
      disabled = disabled)
  }

  def batchInsert(entities: Seq[APICredentialRepository])(implicit session: DBSession = autoSession): Seq[Int] = {
    val params: Seq[Seq[(Symbol, Any)]] = entities.map(entity => 
      Seq(
        'apiKey -> entity.apiKey,
        'apiSecret -> entity.apiSecret,
        'accountId -> entity.accountId,
        'orgId -> entity.orgId,
        'disabled -> entity.disabled))
        SQL("""insert into api_credential(
        api_key,
        api_secret,
        account_id,
        org_id,
        disabled
      ) values (
        {apiKey},
        {apiSecret},
        {accountId},
        {orgId},
        {disabled}
      )""").batchByName(params: _*).apply()
    }

  def save(entity: APICredentialRepository)(implicit session: DBSession = autoSession): APICredentialRepository = {
    withSQL {
      update(APICredentialRepository).set(
        column.apiKey -> entity.apiKey,
        column.apiSecret -> entity.apiSecret,
        column.accountId -> entity.accountId,
        column.orgId -> entity.orgId,
        column.disabled -> entity.disabled
      ).where.eq(column.apiKey, entity.apiKey)
    }.update.apply()
    entity
  }

  def destroy(entity: APICredentialRepository)(implicit session: DBSession = autoSession): Unit = {
    withSQL { delete.from(APICredentialRepository).where.eq(column.apiKey, entity.apiKey) }.update.apply()
  }

}
