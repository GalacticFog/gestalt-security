package com.galacticfog.gestalt.security.data.model

import scalikejdbc._

case class UserAccountRepository(
  accountId: String, 
  username: String, 
  email: String, 
  firstName: String, 
  lastName: String, 
  secret: String, 
  salt: String, 
  hashMethod: String) {

  def save()(implicit session: DBSession = UserAccountRepository.autoSession): UserAccountRepository = UserAccountRepository.save(this)(session)

  def destroy()(implicit session: DBSession = UserAccountRepository.autoSession): Unit = UserAccountRepository.destroy(this)(session)

}
      

object UserAccountRepository extends SQLSyntaxSupport[UserAccountRepository] {

  override val schemaName = Some("public")

  override val tableName = "user_account"

  override val columns = Seq("account_id", "username", "email", "first_name", "last_name", "secret", "salt", "hash_method")

  def apply(uar: SyntaxProvider[UserAccountRepository])(rs: WrappedResultSet): UserAccountRepository = apply(uar.resultName)(rs)
  def apply(uar: ResultName[UserAccountRepository])(rs: WrappedResultSet): UserAccountRepository = new UserAccountRepository(
    accountId = rs.get(uar.accountId),
    username = rs.get(uar.username),
    email = rs.get(uar.email),
    firstName = rs.get(uar.firstName),
    lastName = rs.get(uar.lastName),
    secret = rs.get(uar.secret),
    salt = rs.get(uar.salt),
    hashMethod = rs.get(uar.hashMethod)
  )
      
  val uar = UserAccountRepository.syntax("uar")

  override val autoSession = AutoSession

  def find(accountId: String)(implicit session: DBSession = autoSession): Option[UserAccountRepository] = {
    withSQL {
      select.from(UserAccountRepository as uar).where.eq(uar.accountId, accountId)
    }.map(UserAccountRepository(uar.resultName)).single.apply()
  }
          
  def findAll()(implicit session: DBSession = autoSession): List[UserAccountRepository] = {
    withSQL(select.from(UserAccountRepository as uar)).map(UserAccountRepository(uar.resultName)).list.apply()
  }
          
  def countAll()(implicit session: DBSession = autoSession): Long = {
    withSQL(select(sqls"count(1)").from(UserAccountRepository as uar)).map(rs => rs.long(1)).single.apply().get
  }
          
  def findBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Option[UserAccountRepository] = {
    withSQL {
      select.from(UserAccountRepository as uar).where.append(sqls"${where}")
    }.map(UserAccountRepository(uar.resultName)).single.apply()
  }
      
  def findAllBy(where: SQLSyntax)(implicit session: DBSession = autoSession): List[UserAccountRepository] = {
    withSQL {
      select.from(UserAccountRepository as uar).where.append(sqls"${where}")
    }.map(UserAccountRepository(uar.resultName)).list.apply()
  }
      
  def countBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Long = {
    withSQL {
      select(sqls"count(1)").from(UserAccountRepository as uar).where.append(sqls"${where}")
    }.map(_.long(1)).single.apply().get
  }
      
  def create(
    accountId: String,
    username: String,
    email: String,
    firstName: String,
    lastName: String,
    secret: String,
    salt: String,
    hashMethod: String)(implicit session: DBSession = autoSession): UserAccountRepository = {
    withSQL {
      insert.into(UserAccountRepository).columns(
        column.accountId,
        column.username,
        column.email,
        column.firstName,
        column.lastName,
        column.secret,
        column.salt,
        column.hashMethod
      ).values(
        accountId,
        username,
        email,
        firstName,
        lastName,
        secret,
        salt,
        hashMethod
      )
    }.update.apply()

    UserAccountRepository(
      accountId = accountId,
      username = username,
      email = email,
      firstName = firstName,
      lastName = lastName,
      secret = secret,
      salt = salt,
      hashMethod = hashMethod)
  }

  def save(entity: UserAccountRepository)(implicit session: DBSession = autoSession): UserAccountRepository = {
    withSQL {
      update(UserAccountRepository).set(
        column.accountId -> entity.accountId,
        column.username -> entity.username,
        column.email -> entity.email,
        column.firstName -> entity.firstName,
        column.lastName -> entity.lastName,
        column.secret -> entity.secret,
        column.salt -> entity.salt,
        column.hashMethod -> entity.hashMethod
      ).where.eq(column.accountId, entity.accountId)
    }.update.apply()
    entity
  }
        
  def destroy(entity: UserAccountRepository)(implicit session: DBSession = autoSession): Unit = {
    withSQL { delete.from(UserAccountRepository).where.eq(column.accountId, entity.accountId) }.update.apply()
  }
        
}
