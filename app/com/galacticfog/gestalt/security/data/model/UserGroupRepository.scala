package com.galacticfog.gestalt.security.data.model

import scalikejdbc._

case class UserGroupRepository(
  groupId: String, 
  groupName: String) {

  def save()(implicit session: DBSession = UserGroupRepository.autoSession): UserGroupRepository = UserGroupRepository.save(this)(session)

  def destroy()(implicit session: DBSession = UserGroupRepository.autoSession): Unit = UserGroupRepository.destroy(this)(session)

}
      

object UserGroupRepository extends SQLSyntaxSupport[UserGroupRepository] {

  override val schemaName = Some("public")

  override val tableName = "user_group"

  override val columns = Seq("group_id", "group_name")

  def apply(ugr: SyntaxProvider[UserGroupRepository])(rs: WrappedResultSet): UserGroupRepository = apply(ugr.resultName)(rs)
  def apply(ugr: ResultName[UserGroupRepository])(rs: WrappedResultSet): UserGroupRepository = new UserGroupRepository(
    groupId = rs.get(ugr.groupId),
    groupName = rs.get(ugr.groupName)
  )
      
  val ugr = UserGroupRepository.syntax("ugr")

  override val autoSession = AutoSession

  def find(groupId: String)(implicit session: DBSession = autoSession): Option[UserGroupRepository] = {
    withSQL {
      select.from(UserGroupRepository as ugr).where.eq(ugr.groupId, groupId)
    }.map(UserGroupRepository(ugr.resultName)).single.apply()
  }
          
  def findAll()(implicit session: DBSession = autoSession): List[UserGroupRepository] = {
    withSQL(select.from(UserGroupRepository as ugr)).map(UserGroupRepository(ugr.resultName)).list.apply()
  }
          
  def countAll()(implicit session: DBSession = autoSession): Long = {
    withSQL(select(sqls"count(1)").from(UserGroupRepository as ugr)).map(rs => rs.long(1)).single.apply().get
  }
          
  def findBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Option[UserGroupRepository] = {
    withSQL {
      select.from(UserGroupRepository as ugr).where.append(sqls"${where}")
    }.map(UserGroupRepository(ugr.resultName)).single.apply()
  }
      
  def findAllBy(where: SQLSyntax)(implicit session: DBSession = autoSession): List[UserGroupRepository] = {
    withSQL {
      select.from(UserGroupRepository as ugr).where.append(sqls"${where}")
    }.map(UserGroupRepository(ugr.resultName)).list.apply()
  }
      
  def countBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Long = {
    withSQL {
      select(sqls"count(1)").from(UserGroupRepository as ugr).where.append(sqls"${where}")
    }.map(_.long(1)).single.apply().get
  }
      
  def create(
    groupId: String,
    groupName: String)(implicit session: DBSession = autoSession): UserGroupRepository = {
    withSQL {
      insert.into(UserGroupRepository).columns(
        column.groupId,
        column.groupName
      ).values(
        groupId,
        groupName
      )
    }.update.apply()

    UserGroupRepository(
      groupId = groupId,
      groupName = groupName)
  }

  def save(entity: UserGroupRepository)(implicit session: DBSession = autoSession): UserGroupRepository = {
    withSQL {
      update(UserGroupRepository).set(
        column.groupId -> entity.groupId,
        column.groupName -> entity.groupName
      ).where.eq(column.groupId, entity.groupId)
    }.update.apply()
    entity
  }
        
  def destroy(entity: UserGroupRepository)(implicit session: DBSession = autoSession): Unit = {
    withSQL { delete.from(UserGroupRepository).where.eq(column.groupId, entity.groupId) }.update.apply()
  }
        
}
