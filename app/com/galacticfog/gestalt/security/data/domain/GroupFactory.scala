package com.galacticfog.gestalt.security.data.domain

import java.util.UUID

import com.galacticfog.gestalt.patch.PatchOp
import com.galacticfog.gestalt.security.api.{GROUP, GestaltGroup}
import com.galacticfog.gestalt.security.api.errors.{BadRequestException, ConflictException}
import com.galacticfog.gestalt.security.data.model._
import org.postgresql.util.PSQLException
import scalikejdbc._
import scalikejdbc.TxBoundary.Try._

import scala.util.{Failure, Try}


object GroupFactory extends SQLSyntaxSupport[UserGroupRepository] {

  def instance = this

  override val autoSession = AutoSession

  def find(groupId: UUID)(implicit session: DBSession = autoSession): Option[UserGroupRepository] = {
    UserGroupRepository.find(groupId)
  }

  def delete(groupId: UUID)(implicit session: DBSession = autoSession): Boolean = {
    find(groupId) match {
      case Some(grp) =>
        UserGroupRepository.destroy(grp)
        true
      case None =>
        false
    }
  }

  def create(name: String,
             description: Option[String],
             dirId: UUID,
             maybeParentOrg: Option[UUID])
            (implicit session: DBSession = autoSession): Try[UserGroupRepository] = {
    Try {
      UserGroupRepository.create(
        id = UUID.randomUUID(),
        dirId = dirId,
        name = name,
        disabled = false,
        parentOrg = maybeParentOrg,
        description = description
      )
    } recoverWith {
      case t: PSQLException if t.getSQLState == "23505" || t.getSQLState == "23514" =>
        t.getServerErrorMessage.getConstraint match {
          case "account_group_dir_id_name_key" => Failure(ConflictException(
            resource = "",
            message = "group name already exists in directory",
            developerMessage = "A group with the specified name already exists in the specified directory."
          ))
          case _ => Failure(t)
        }
    }
  }

  /**
    * Deep-query of all application groups, with wildcard matching.
    *
    * An application group is a group that is:
    *   - in a directory assigned by a sibling group (including self)
    *   - in an directory assigned to the app (parent)
    *
    * @param appId the specified app
    * @param nameQuery query parameter for the search, which may include '*' wildcards
    * @param session
    * @return All application groups matching the query
    */
  def lookupAppGroups(appId: UUID, nameQuery: String)
                     (implicit session: DBSession = autoSession): Seq[GestaltGroup] = {
    val (groupMappings, dirMappings) = AppFactory.listAccountStoreMappings(appId).partition(_.storeType == "GROUP")
    // direct groups from parent, via their directories
    val dirGroups = dirMappings
      .flatMap( asm => DirectoryFactory.find(asm.accountStoreId.asInstanceOf[UUID]) )
      .flatMap( _.lookupGroups(nameQuery) )
    // indirect groups from siblings
    val indGroups = groupMappings
      .flatMap( asm => GroupFactory.find(asm.accountStoreId.asInstanceOf[UUID]))
      .flatMap( grp => DirectoryFactory.find(grp.dirId.asInstanceOf[UUID]) )
      .flatMap( _.lookupGroups(nameQuery) )
    (indGroups ++ dirGroups).distinct
  }

  def listByDirectoryId(dirId: UUID)(implicit session: DBSession = autoSession): Seq[UserGroupRepository] = {
    UserGroupRepository.findAllBy(sqls"dir_id = ${dirId}")
  }

  def listGroupAccounts(groupId: UUID)(implicit session: DBSession = autoSession): Seq[UserAccountRepository] = {
    val (acc, axg) = (
      UserAccountRepository.syntax("acc"),
      GroupMembershipRepository.syntax("axg")
      )
    sql"""select ${acc.result.*}
          from ${UserAccountRepository.as(acc)},${GroupMembershipRepository.as(axg)}
          where ${axg.groupId} = ${groupId} and ${axg.accountId} = ${acc.id}
      """.map(UserAccountRepository(acc)).list.apply()
  }

  def listAccountGroups(accountId: UUID)(implicit session: DBSession = autoSession): Seq[UserGroupRepository] = {
    val (grp, axg) = (
      UserGroupRepository.syntax("grp"),
      GroupMembershipRepository.syntax("axg")
      )
    sql"""select ${grp.result.*}
          from ${UserGroupRepository.as(grp)},${GroupMembershipRepository.as(axg)}
          where ${axg.accountId} = ${accountId} and ${axg.groupId} = ${grp.id}
      """.map(UserGroupRepository(grp)).list.apply()
  }

  def removeAccountFromGroup(groupId: UUID, accountId: UUID)(implicit session: DBSession = autoSession): Unit = {
    Try{GroupMembershipRepository.destroy(GroupMembershipRepository(
      accountId = accountId,
      groupId = groupId
    ))}
  }

  def updateGroupMembership(groupId: UUID, payload: Seq[PatchOp])(implicit session: DBSession = autoSession): Try[Seq[UserAccountRepository]] = {
    DB localTx { implicit session =>
      val ops = payload map { p => for {
        accountId <- Try{p.value.get.as[UUID]}
        addRemove <- p.op.toLowerCase match {
          case "add" => addAccountToGroup(groupId, accountId) map {_.accountId.asInstanceOf[UUID]}
          case "remove" => Try {
            removeAccountFromGroup(groupId, accountId)
            accountId
          }
          case _ => Failure(BadRequestException("", "invalid op", "group membership update supports only operations add and remove"))
        }
      } yield addRemove}
      Try{ops map {_.get}} map {_ => listGroupAccounts(groupId)}
    }
  }

  def addAccountToGroup(groupId: UUID, accountId: UUID)(implicit session: DBSession = autoSession): Try[GroupMembershipRepository] = {
    UserGroupRepository.find(groupId) match {
      case None => Failure(BadRequestException(
        resource = s"/groups/${groupId}",
        message = "cannot add account to non-existent group",
        developerMessage = "Cannot add account to non-existent group. Verify that the correct group ID was provided."
      ))
      case Some(group) => UserAccountRepository.find(accountId) match {
        case None => Failure(BadRequestException(
          resource = s"/accounts/${groupId}",
          message = "cannot add non-existent account to group",
          developerMessage = "Cannot add non-existent account to group. Verify that the correct account ID was provided."
        ))
        case Some(account) => if (group.dirId.asInstanceOf[UUID] != account.dirId.asInstanceOf[UUID]) Failure(BadRequestException(
          resource = s"/groups/${groupId}",
          message = "account and group were not in the same directory",
          developerMessage = "Account and group were not in the same directory. Adding an account to a group requires that they are contained in the same directory."
        ))
        Try(GroupMembershipRepository.create(
          accountId = accountId,
          groupId = groupId
        ))
      }
    }
  }

  def getAppGroupMapping(appId: UUID, groupId: UUID)(implicit session: DBSession = autoSession): Option[UserGroupRepository] = {
    val (grp, asm) = (
      UserGroupRepository.syntax("grp"),
      AccountStoreMappingRepository.syntax("asm")
      )
    sql"""select distinct ${grp.result.*}
          from ${UserGroupRepository.as(grp)},${AccountStoreMappingRepository.as(asm)}
          where (${asm.appId} = ${appId} and ${asm.storeType} = 'GROUP' and ${grp.id} = ${asm.accountStoreId} and ${grp.id} = ${groupId}) OR
                (${asm.appId} = ${appId} and ${asm.storeType} = 'DIRECTORY' and ${grp.dirId} = ${asm.accountStoreId} and ${grp.id} = ${groupId})
      """.map(UserGroupRepository(grp)).list.apply().headOption
  }

  /*
    This is:
      all Groups grp such that
        there exists Account acc where acc is a member of grp and acc can authenticate in the app
    However, we'd like to avoid looking at the group membership table, so instead, we'll use the superset:
      all Groups grp such that
        grp belongs to a Directory dir that is mapped to the app
          or
        grp belongs to a Directory that contains a Group grpm that is mapped to the app
      So, we need all directly and indirectly mapped Directories
   */
  def queryShadowedAppGroups(appId: UUID, nameQuery: Option[String])(implicit session: DBSession = autoSession): Seq[UserGroupRepository]  = {
    val (grp, grp2, asm) = (
      UserGroupRepository.syntax("grp"),
      UserGroupRepository.syntax("grp2"),
      AccountStoreMappingRepository.syntax("asm")
    )
    val nq = nameQuery.map(_.replace("*","%")).getOrElse("%")
    sql"""
         SELECT ${grp.result.*}
         FROM ${UserGroupRepository.as(grp)}
         WHERE grp.name LIKE ${nq} AND EXISTS(
           (SELECT 1 FROM ${AccountStoreMappingRepository.as(asm)} INNER JOIN ${UserGroupRepository.as(grp2)} ON ${asm.appId} = ${appId} AND ${asm.storeType} = 'GROUP' AND ${asm.accountStoreId} = ${grp2.id} AND ${grp2.dirId} = ${grp.dirId})
             UNION ALL
           (SELECT 1 FROM ${AccountStoreMappingRepository.as(asm)} WHERE ${asm.appId} = ${appId} AND ${asm.storeType} = 'DIRECTORY' AND ${asm.accountStoreId} = ${grp.dirId})
         )
      """.map(UserGroupRepository(grp)).list.apply()
  }

  def findInDirectoryByName(dirId: UUID, groupName: String)(implicit session: DBSession = autoSession): Option[UserGroupRepository] = {
    UserGroupRepository.findBy(sqls"dir_id = ${dirId} and name = ${groupName}")
  }

  def queryShadowedDirectoryGroups(dirId: Option[UUID], nameQuery: Option[String])
                                  (implicit session: DBSession = autoSession): List[UserGroupRepository] = {
    val g = UserGroupRepository.syntax("g")
    withSQL {
      select
        .from(UserGroupRepository as g)
        .where(sqls.toAndConditionOpt(
          dirId.map(id => sqls.eq(g.dirId, id)),
          nameQuery.map(q => sqls.like(g.name, q.replace("*","%")))
        ))
    }.map{UserGroupRepository(g)}.list.apply()
  }

}
