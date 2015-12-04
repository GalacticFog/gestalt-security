package com.galacticfog.gestalt.security.data.model

import scalikejdbc._

case class UserAccountRepository(
  id: Any,
  dirId: Any,
  username: String,
  email: Option[String] = None,
  phoneNumber: Option[String] = None,
  firstName: String,
  lastName: String,
  hashMethod: String,
  salt: String,
  secret: String,
  disabled: Boolean) {

  def save()(implicit session: DBSession = UserAccountRepository.autoSession): UserAccountRepository = UserAccountRepository.save(this)(session)

  def destroy()(implicit session: DBSession = UserAccountRepository.autoSession): Unit = UserAccountRepository.destroy(this)(session)

}


object UserAccountRepository extends SQLSyntaxSupport[UserAccountRepository] {

  override val schemaName = Some("public")

  override val tableName = "account"

  override val columns = Seq("id", "dir_id", "username", "email", "phone_number", "first_name", "last_name", "hash_method", "salt", "secret", "disabled")

  def apply(uar: SyntaxProvider[UserAccountRepository])(rs: WrappedResultSet): UserAccountRepository = apply(uar.resultName)(rs)
  def apply(uar: ResultName[UserAccountRepository])(rs: WrappedResultSet): UserAccountRepository = new UserAccountRepository(
    id = rs.any(uar.id),
    dirId = rs.any(uar.dirId),
    username = rs.get(uar.username),
    email = rs.get(uar.email),
    phoneNumber = rs.get(uar.phoneNumber),
    firstName = rs.get(uar.firstName),
    lastName = rs.get(uar.lastName),
    hashMethod = rs.get(uar.hashMethod),
    salt = rs.get(uar.salt),
    secret = rs.get(uar.secret),
    disabled = rs.get(uar.disabled)
  )

  val uar = UserAccountRepository.syntax("uar")

  override val autoSession = AutoSession

  def find(id: Any)(implicit session: DBSession = autoSession): Option[UserAccountRepository] = {
    withSQL {
      select.from(UserAccountRepository as uar).where.eq(uar.id, id)
    }.map(UserAccountRepository(uar.resultName)).single.apply()
  }

  def findAll()(implicit session: DBSession = autoSession): List[UserAccountRepository] = {
    withSQL(select.from(UserAccountRepository as uar)).map(UserAccountRepository(uar.resultName)).list.apply()
  }

  def countAll()(implicit session: DBSession = autoSession): Long = {
    withSQL(select(sqls.count).from(UserAccountRepository as uar)).map(rs => rs.long(1)).single.apply().get
  }

  def findBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Option[UserAccountRepository] = {
    withSQL {
      select.from(UserAccountRepository as uar).where.append(where)
    }.map(UserAccountRepository(uar.resultName)).single.apply()
  }

  def findAllBy(where: SQLSyntax)(implicit session: DBSession = autoSession): List[UserAccountRepository] = {
    withSQL {
      select.from(UserAccountRepository as uar).where.append(where)
    }.map(UserAccountRepository(uar.resultName)).list.apply()
  }

  def countBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Long = {
    withSQL {
      select(sqls.count).from(UserAccountRepository as uar).where.append(where)
    }.map(_.long(1)).single.apply().get
  }

  def create(
    id: Any,
    dirId: Any,
    username: String,
    email: Option[String] = None,
    phoneNumber: Option[String] = None,
    firstName: String,
    lastName: String,
    hashMethod: String,
    salt: String,
    secret: String,
    disabled: Boolean)(implicit session: DBSession = autoSession): UserAccountRepository = {
    withSQL {
      insert.into(UserAccountRepository).columns(
        column.id,
        column.dirId,
        column.username,
        column.email,
        column.phoneNumber,
        column.firstName,
        column.lastName,
        column.hashMethod,
        column.salt,
        column.secret,
        column.disabled
      ).values(
        id,
        dirId,
        username,
        email,
        phoneNumber,
        firstName,
        lastName,
        hashMethod,
        salt,
        secret,
        disabled
      )
    }.update.apply()

    UserAccountRepository(
      id = id,
      dirId = dirId,
      username = username,
      email = email,
      phoneNumber = phoneNumber,
      firstName = firstName,
      lastName = lastName,
      hashMethod = hashMethod,
      salt = salt,
      secret = secret,
      disabled = disabled)
  }

  def batchInsert(entities: Seq[UserAccountRepository])(implicit session: DBSession = autoSession): Seq[Int] = {
    val params: Seq[Seq[(Symbol, Any)]] = entities.map(entity => 
      Seq(
        'id -> entity.id,
        'dirId -> entity.dirId,
        'username -> entity.username,
        'email -> entity.email,
        'phoneNumber -> entity.phoneNumber,
        'firstName -> entity.firstName,
        'lastName -> entity.lastName,
        'hashMethod -> entity.hashMethod,
        'salt -> entity.salt,
        'secret -> entity.secret,
        'disabled -> entity.disabled))
        SQL("""insert into account(
        id,
        dir_id,
        username,
        email,
        phone_number,
        first_name,
        last_name,
        hash_method,
        salt,
        secret,
        disabled
      ) values (
        {id},
        {dirId},
        {username},
        {email},
        {phoneNumber},
        {firstName},
        {lastName},
        {hashMethod},
        {salt},
        {secret},
        {disabled}
      )""").batchByName(params: _*).apply()
    }

  def save(entity: UserAccountRepository)(implicit session: DBSession = autoSession): UserAccountRepository = {
    withSQL {
      update(UserAccountRepository).set(
        column.id -> entity.id,
        column.dirId -> entity.dirId,
        column.username -> entity.username,
        column.email -> entity.email,
        column.phoneNumber -> entity.phoneNumber,
        column.firstName -> entity.firstName,
        column.lastName -> entity.lastName,
        column.hashMethod -> entity.hashMethod,
        column.salt -> entity.salt,
        column.secret -> entity.secret,
        column.disabled -> entity.disabled
      ).where.eq(column.id, entity.id)
    }.update.apply()
    entity
  }

  def destroy(entity: UserAccountRepository)(implicit session: DBSession = autoSession): Unit = {
    withSQL { delete.from(UserAccountRepository).where.eq(column.id, entity.id) }.update.apply()
  }

}
