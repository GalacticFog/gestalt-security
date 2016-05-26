package com.galacticfog.gestalt.security.data.model

import scalikejdbc._

case class APICredentialRepository(
  apiKey: Any,
  apiSecret: String,
  accountId: Any,
  issuedOrgId: Option[Any] = None,
  disabled: Boolean,
  parentApikey: Option[Any] = None) {

  def save()(implicit session: DBSession = APICredentialRepository.autoSession): APICredentialRepository = APICredentialRepository.save(this)(session)

  def destroy()(implicit session: DBSession = APICredentialRepository.autoSession): Unit = APICredentialRepository.destroy(this)(session)

}


object APICredentialRepository extends SQLSyntaxSupport[APICredentialRepository] {

  override val schemaName = Some("public")

  override val tableName = "api_credential"

  override val columns = Seq("api_key", "api_secret", "account_id", "issued_org_id", "disabled", "parent_apikey")

  def apply(apicr: SyntaxProvider[APICredentialRepository])(rs: WrappedResultSet): APICredentialRepository = apply(apicr.resultName)(rs)
  def apply(apicr: ResultName[APICredentialRepository])(rs: WrappedResultSet): APICredentialRepository = new APICredentialRepository(
    apiKey = rs.any(apicr.apiKey),
    apiSecret = rs.get(apicr.apiSecret),
    accountId = rs.any(apicr.accountId),
    issuedOrgId = rs.anyOpt(apicr.issuedOrgId),
    disabled = rs.get(apicr.disabled),
    parentApikey = rs.anyOpt(apicr.parentApikey)
  )

  val apicr = APICredentialRepository.syntax("apicr")

  override val autoSession = AutoSession

  def find(apiKey: Any)(implicit session: DBSession = autoSession): Option[APICredentialRepository] = {
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
    apiKey: Any,
    apiSecret: String,
    accountId: Any,
    issuedOrgId: Option[Any] = None,
    disabled: Boolean,
    parentApikey: Option[Any] = None)(implicit session: DBSession = autoSession): APICredentialRepository = {
    withSQL {
      insert.into(APICredentialRepository).columns(
        column.apiKey,
        column.apiSecret,
        column.accountId,
        column.issuedOrgId,
        column.disabled,
        column.parentApikey
      ).values(
        apiKey,
        apiSecret,
        accountId,
        issuedOrgId,
        disabled,
        parentApikey
      )
    }.update.apply()

    APICredentialRepository(
      apiKey = apiKey,
      apiSecret = apiSecret,
      accountId = accountId,
      issuedOrgId = issuedOrgId,
      disabled = disabled,
      parentApikey = parentApikey)
  }

  def batchInsert(entities: Seq[APICredentialRepository])(implicit session: DBSession = autoSession): Seq[Int] = {
    val params: Seq[Seq[(Symbol, Any)]] = entities.map(entity => 
      Seq(
        'apiKey -> entity.apiKey,
        'apiSecret -> entity.apiSecret,
        'accountId -> entity.accountId,
        'issuedOrgId -> entity.issuedOrgId,
        'disabled -> entity.disabled,
        'parentApikey -> entity.parentApikey))
        SQL("""insert into api_credential(
        api_key,
        api_secret,
        account_id,
        issued_org_id,
        disabled,
        parent_apikey
      ) values (
        {apiKey},
        {apiSecret},
        {accountId},
        {issuedOrgId},
        {disabled},
        {parentApikey}
      )""").batchByName(params: _*).apply()
    }

  def save(entity: APICredentialRepository)(implicit session: DBSession = autoSession): APICredentialRepository = {
    withSQL {
      update(APICredentialRepository).set(
        column.apiKey -> entity.apiKey,
        column.apiSecret -> entity.apiSecret,
        column.accountId -> entity.accountId,
        column.issuedOrgId -> entity.issuedOrgId,
        column.disabled -> entity.disabled,
        column.parentApikey -> entity.parentApikey
      ).where.eq(column.apiKey, entity.apiKey)
    }.update.apply()
    entity
  }

  def destroy(entity: APICredentialRepository)(implicit session: DBSession = autoSession): Unit = {
    withSQL { delete.from(APICredentialRepository).where.eq(column.apiKey, entity.apiKey) }.update.apply()
  }

}
