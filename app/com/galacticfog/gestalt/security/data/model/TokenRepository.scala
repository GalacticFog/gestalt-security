package com.galacticfog.gestalt.security.data.model

import scalikejdbc._
import org.joda.time.DateTime

case class TokenRepository(
  id: Any,
  accountId: Any,
  issuedAt: DateTime,
  expiresAt: DateTime,
  refreshToken: Option[Any] = None,
  issuedOrgId: Option[Any] = None,
  tokenType: String,
  parentApikey: Option[Any] = None) {

  def save()(implicit session: DBSession = TokenRepository.autoSession): TokenRepository = TokenRepository.save(this)(session)

  def destroy()(implicit session: DBSession = TokenRepository.autoSession): Unit = TokenRepository.destroy(this)(session)

}


object TokenRepository extends SQLSyntaxSupport[TokenRepository] {

  override val schemaName = Some("public")

  override val tableName = "token"

  override val columns = Seq("id", "account_id", "issued_at", "expires_at", "refresh_token", "issued_org_id", "token_type", "parent_apikey")

  def apply(tr: SyntaxProvider[TokenRepository])(rs: WrappedResultSet): TokenRepository = apply(tr.resultName)(rs)
  def apply(tr: ResultName[TokenRepository])(rs: WrappedResultSet): TokenRepository = new TokenRepository(
    id = rs.any(tr.id),
    accountId = rs.any(tr.accountId),
    issuedAt = rs.get(tr.issuedAt),
    expiresAt = rs.get(tr.expiresAt),
    refreshToken = rs.anyOpt(tr.refreshToken),
    issuedOrgId = rs.anyOpt(tr.issuedOrgId),
    tokenType = rs.get(tr.tokenType),
    parentApikey = rs.anyOpt(tr.parentApikey)
  )

  val tr = TokenRepository.syntax("tr")

  override val autoSession = AutoSession

  def find(id: Any)(implicit session: DBSession = autoSession): Option[TokenRepository] = {
    withSQL {
      select.from(TokenRepository as tr).where.eq(tr.id, id)
    }.map(TokenRepository(tr.resultName)).single.apply()
  }

  def findAll()(implicit session: DBSession = autoSession): List[TokenRepository] = {
    withSQL(select.from(TokenRepository as tr)).map(TokenRepository(tr.resultName)).list.apply()
  }

  def countAll()(implicit session: DBSession = autoSession): Long = {
    withSQL(select(sqls.count).from(TokenRepository as tr)).map(rs => rs.long(1)).single.apply().get
  }

  def findBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Option[TokenRepository] = {
    withSQL {
      select.from(TokenRepository as tr).where.append(where)
    }.map(TokenRepository(tr.resultName)).single.apply()
  }

  def findAllBy(where: SQLSyntax)(implicit session: DBSession = autoSession): List[TokenRepository] = {
    withSQL {
      select.from(TokenRepository as tr).where.append(where)
    }.map(TokenRepository(tr.resultName)).list.apply()
  }

  def countBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Long = {
    withSQL {
      select(sqls.count).from(TokenRepository as tr).where.append(where)
    }.map(_.long(1)).single.apply().get
  }

  def create(
    id: Any,
    accountId: Any,
    issuedAt: DateTime,
    expiresAt: DateTime,
    refreshToken: Option[Any] = None,
    issuedOrgId: Option[Any] = None,
    tokenType: String,
    parentApikey: Option[Any] = None)(implicit session: DBSession = autoSession): TokenRepository = {
    withSQL {
      insert.into(TokenRepository).columns(
        column.id,
        column.accountId,
        column.issuedAt,
        column.expiresAt,
        column.refreshToken,
        column.issuedOrgId,
        column.tokenType,
        column.parentApikey
      ).values(
        id,
        accountId,
        issuedAt,
        expiresAt,
        refreshToken,
        issuedOrgId,
        tokenType,
        parentApikey
      )
    }.update.apply()

    TokenRepository(
      id = id,
      accountId = accountId,
      issuedAt = issuedAt,
      expiresAt = expiresAt,
      refreshToken = refreshToken,
      issuedOrgId = issuedOrgId,
      tokenType = tokenType,
      parentApikey = parentApikey)
  }

  def batchInsert(entities: Seq[TokenRepository])(implicit session: DBSession = autoSession): Seq[Int] = {
    val params: Seq[Seq[(Symbol, Any)]] = entities.map(entity => 
      Seq(
        'id -> entity.id,
        'accountId -> entity.accountId,
        'issuedAt -> entity.issuedAt,
        'expiresAt -> entity.expiresAt,
        'refreshToken -> entity.refreshToken,
        'issuedOrgId -> entity.issuedOrgId,
        'tokenType -> entity.tokenType,
        'parentApikey -> entity.parentApikey))
        SQL("""insert into token(
        id,
        account_id,
        issued_at,
        expires_at,
        refresh_token,
        issued_org_id,
        token_type,
        parent_apikey
      ) values (
        {id},
        {accountId},
        {issuedAt},
        {expiresAt},
        {refreshToken},
        {issuedOrgId},
        {tokenType},
        {parentApikey}
      )""").batchByName(params: _*).apply()
    }

  def save(entity: TokenRepository)(implicit session: DBSession = autoSession): TokenRepository = {
    withSQL {
      update(TokenRepository).set(
        column.id -> entity.id,
        column.accountId -> entity.accountId,
        column.issuedAt -> entity.issuedAt,
        column.expiresAt -> entity.expiresAt,
        column.refreshToken -> entity.refreshToken,
        column.issuedOrgId -> entity.issuedOrgId,
        column.tokenType -> entity.tokenType,
        column.parentApikey -> entity.parentApikey
      ).where.eq(column.id, entity.id)
    }.update.apply()
    entity
  }

  def destroy(entity: TokenRepository)(implicit session: DBSession = autoSession): Unit = {
    withSQL { delete.from(TokenRepository).where.eq(column.id, entity.id) }.update.apply()
  }

}
