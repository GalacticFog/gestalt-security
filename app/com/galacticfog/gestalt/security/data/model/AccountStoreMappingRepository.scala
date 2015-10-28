package com.galacticfog.gestalt.security.data.model

import scalikejdbc._

case class AccountStoreMappingRepository(
  id: Any,
  appId: Any,
  storeType: String,
  accountStoreId: Any,
  defaultAccountStore: Option[Any] = None,
  defaultGroupStore: Option[Any] = None) {

  def save()(implicit session: DBSession = AccountStoreMappingRepository.autoSession): AccountStoreMappingRepository = AccountStoreMappingRepository.save(this)(session)

  def destroy()(implicit session: DBSession = AccountStoreMappingRepository.autoSession): Unit = AccountStoreMappingRepository.destroy(this)(session)

}


object AccountStoreMappingRepository extends SQLSyntaxSupport[AccountStoreMappingRepository] {

  override val schemaName = Some("public")

  override val tableName = "account_store_mapping"

  override val columns = Seq("id", "app_id", "store_type", "account_store_id", "default_account_store", "default_group_store")

  def apply(asmr: SyntaxProvider[AccountStoreMappingRepository])(rs: WrappedResultSet): AccountStoreMappingRepository = apply(asmr.resultName)(rs)
  def apply(asmr: ResultName[AccountStoreMappingRepository])(rs: WrappedResultSet): AccountStoreMappingRepository = new AccountStoreMappingRepository(
    id = rs.any(asmr.id),
    appId = rs.any(asmr.appId),
    storeType = rs.get(asmr.storeType),
    accountStoreId = rs.any(asmr.accountStoreId),
    defaultAccountStore = rs.anyOpt(asmr.defaultAccountStore),
    defaultGroupStore = rs.anyOpt(asmr.defaultGroupStore)
  )

  val asmr = AccountStoreMappingRepository.syntax("asmr")

  override val autoSession = AutoSession

  def find(id: Any)(implicit session: DBSession = autoSession): Option[AccountStoreMappingRepository] = {
    withSQL {
      select.from(AccountStoreMappingRepository as asmr).where.eq(asmr.id, id)
    }.map(AccountStoreMappingRepository(asmr.resultName)).single.apply()
  }

  def findAll()(implicit session: DBSession = autoSession): List[AccountStoreMappingRepository] = {
    withSQL(select.from(AccountStoreMappingRepository as asmr)).map(AccountStoreMappingRepository(asmr.resultName)).list.apply()
  }

  def countAll()(implicit session: DBSession = autoSession): Long = {
    withSQL(select(sqls.count).from(AccountStoreMappingRepository as asmr)).map(rs => rs.long(1)).single.apply().get
  }

  def findBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Option[AccountStoreMappingRepository] = {
    withSQL {
      select.from(AccountStoreMappingRepository as asmr).where.append(where)
    }.map(AccountStoreMappingRepository(asmr.resultName)).single.apply()
  }

  def findAllBy(where: SQLSyntax)(implicit session: DBSession = autoSession): List[AccountStoreMappingRepository] = {
    withSQL {
      select.from(AccountStoreMappingRepository as asmr).where.append(where)
    }.map(AccountStoreMappingRepository(asmr.resultName)).list.apply()
  }

  def countBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Long = {
    withSQL {
      select(sqls.count).from(AccountStoreMappingRepository as asmr).where.append(where)
    }.map(_.long(1)).single.apply().get
  }

  def create(
    id: Any,
    appId: Any,
    storeType: String,
    accountStoreId: Any,
    defaultAccountStore: Option[Any] = None,
    defaultGroupStore: Option[Any] = None)(implicit session: DBSession = autoSession): AccountStoreMappingRepository = {
    withSQL {
      insert.into(AccountStoreMappingRepository).columns(
        column.id,
        column.appId,
        column.storeType,
        column.accountStoreId,
        column.defaultAccountStore,
        column.defaultGroupStore
      ).values(
        id,
        appId,
        storeType,
        accountStoreId,
        defaultAccountStore,
        defaultGroupStore
      )
    }.update.apply()

    AccountStoreMappingRepository(
      id = id,
      appId = appId,
      storeType = storeType,
      accountStoreId = accountStoreId,
      defaultAccountStore = defaultAccountStore,
      defaultGroupStore = defaultGroupStore)
  }

  def batchInsert(entities: Seq[AccountStoreMappingRepository])(implicit session: DBSession = autoSession): Seq[Int] = {
    val params: Seq[Seq[(Symbol, Any)]] = entities.map(entity => 
      Seq(
        'id -> entity.id,
        'appId -> entity.appId,
        'storeType -> entity.storeType,
        'accountStoreId -> entity.accountStoreId,
        'defaultAccountStore -> entity.defaultAccountStore,
        'defaultGroupStore -> entity.defaultGroupStore))
        SQL("""insert into account_store_mapping(
        id,
        app_id,
        store_type,
        account_store_id,
        default_account_store,
        default_group_store
      ) values (
        {id},
        {appId},
        {storeType},
        {accountStoreId},
        {defaultAccountStore},
        {defaultGroupStore}
      )""").batchByName(params: _*).apply()
    }

  def save(entity: AccountStoreMappingRepository)(implicit session: DBSession = autoSession): AccountStoreMappingRepository = {
    withSQL {
      update(AccountStoreMappingRepository).set(
        column.id -> entity.id,
        column.appId -> entity.appId,
        column.storeType -> entity.storeType,
        column.accountStoreId -> entity.accountStoreId,
        column.defaultAccountStore -> entity.defaultAccountStore,
        column.defaultGroupStore -> entity.defaultGroupStore
      ).where.eq(column.id, entity.id)
    }.update.apply()
    entity
  }

  def destroy(entity: AccountStoreMappingRepository)(implicit session: DBSession = autoSession): Unit = {
    withSQL { delete.from(AccountStoreMappingRepository).where.eq(column.id, entity.id) }.update.apply()
  }

}
