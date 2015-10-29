package com.galacticfog.gestalt.security.data.domain

import java.util.UUID

import com.galacticfog.gestalt.security.api.errors.{UnknownAPIException, BadRequestException, ResourceNotFoundException}
import com.galacticfog.gestalt.security.api.{DIRECTORY, GROUP, GestaltPasswordCredential, GestaltAccountCreateWithRights}
import com.galacticfog.gestalt.security.data.model._
import com.galacticfog.gestalt.security.utils.SecureIdGenerator
import org.mindrot.jbcrypt.BCrypt
import scalikejdbc._
import scalikejdbc.TxBoundary.Try._
import scala.util.{Failure, Success, Try}

object AppFactory extends SQLSyntaxSupport[UserAccountRepository] {

  override val autoSession = AutoSession

  def create(orgId: UUID, name: String, isServiceOrg: Boolean) = {
    Try{GestaltAppRepository.create(
      id = UUID.randomUUID(),
      name = name,
      orgId = orgId,
      serviceOrgId = if (isServiceOrg) Some(orgId) else None
    )}
  }

  def listAccountStoreMappings(appId: UUID): Seq[AccountStoreMappingRepository] = {
    AccountStoreMappingRepository.findAllBy(sqls"app_id = ${appId}")
  }

  def mapGroupToApp(appId: UUID, groupId: UUID, defaultAccountStore: Boolean): Try[AccountStoreMappingRepository] = {
    Try {
      AccountStoreMappingRepository.create(
        id = UUID.randomUUID,
        appId = appId,
        storeType = "GROUP",
        accountStoreId = groupId,
        defaultAccountStore = if (defaultAccountStore) Some(appId) else None,
        defaultGroupStore = None
      )
    }
  }

  def getDefaultAccountStore(appId: UUID): Either[GestaltDirectoryRepository,UserGroupRepository] = {
    AccountStoreMappingRepository.findBy(sqls"default_account_store = ${appId}") match {
      case Some(asm) =>
        asm.storeType match {
          case "GROUP" =>
            UserGroupRepository.find(asm.accountStoreId) match {
              case Some(group) => Right(group)
              case None =>
                throw new UnknownAPIException(500, resource = s"/groups/${asm.accountStoreId}", message = "error accessing group corresponding to app's default account store", developerMessage = "")
            }
          case "DIRECTORY" =>
            GestaltDirectoryRepository.find(asm.accountStoreId) match {
              case Some(dir) => Left(dir)
              case None =>
                throw new UnknownAPIException(500, resource = s"/directories/${asm.accountStoreId}", message = "error accessing directory corresponding to app's default account store", developerMessage = "")
            }
        }
      case None =>
        throw new BadRequestException(
          resource = s"/apps/${appId}",
          message = "app does not have a default account store",
          developerMessage = "Some operation attempted to use the app's default account store, but it does not have one. You will need to add one or avoid relying on its existence.")
    }
  }

  def createAccountInApp(appId: UUID, create: GestaltAccountCreateWithRights): Try[UserAccountRepository] = DB localTx { implicit session =>
    // have to find the default account store for the app
    // if it's a directory, add the account to the directory
    // if it's a group, add the account to the group's directory and then to the group
    // then add the account to any groups specified in the create request
    for {
      app <- Try{AppFactory.findByAppId(appId).get}
      cred <- Try{create.credential.asInstanceOf[GestaltPasswordCredential]}
      asm <- Try{getDefaultAccountStore(appId)}
      dirId = asm.fold(_.id, _.dirId).asInstanceOf[UUID]
      newUser <- Try{UserAccountRepository.create(
        id = UUID.randomUUID(),
        dirId = dirId,
        username = create.username,
        email = create.email,
        phoneNumber = if (create.phoneNumber.isEmpty) None else Some(create.phoneNumber),
        firstName = create.firstName,
        lastName = create.lastName,
        hashMethod = "bcrypt",
        secret = BCrypt.hashpw(cred.password, BCrypt.gensalt()),
        salt = "",
        disabled = false
      )}
    } yield {
      // add grants
      val newUserId = newUser.id.asInstanceOf[UUID]
      create.rights foreach {_.foreach { grant =>
        RightGrantRepository.create(
          grantId = UUID.randomUUID,
          appId = appId,
          groupId = None,
          accountId = Some(newUser.id),
          grantName = grant.grantName,
          grantValue = grant.grantValue)
      }}
      // add groups
      (create.groups.toSeq.flatten ++ asm.right.toSeq.map{_.id.asInstanceOf[UUID]}).distinct.map {
        groupId => GroupMembershipRepository.create(accountId = newUserId, groupId = groupId)
      }
      newUser
    }
  }

  def findByAppName(orgId: UUID, appName: String): Try[GestaltAppRepository] = {
    GestaltAppRepository.findBy(sqls"org_id=${orgId} AND name=${appName}") map {Success.apply} getOrElse {
      Failure(ResourceNotFoundException("app","could not find specified application","Could not find specified application. Ensure that you are providing the application ID and the correct organization ID.")      )
    }
  }

  def findServiceAppForOrg(orgId: UUID)(implicit session: DBSession = autoSession): Option[GestaltAppRepository] = {
    val (a,o) = (
      GestaltAppRepository.syntax("a"),
      GestaltOrgRepository.syntax("o")
      )
    sql"""
    select
      ${a.result.*}
    from
      ${GestaltAppRepository.as(a)} inner join ${GestaltOrgRepository.as(o)} on ${o.id} = ${orgId} and ${a.serviceOrgId} = ${o.id}
    """.map(GestaltAppRepository(a)).single.apply()
  }

  def findServiceAppForFQON(fqon: String)(implicit session: DBSession = autoSession): Option[GestaltAppRepository] = {
    val (a,o) = (
        GestaltAppRepository.syntax("a"),
        GestaltOrgRepository.syntax("o")
      )
    sql"""
    select
      ${a.result.*}
    from
      ${GestaltAppRepository.as(a)} inner join ${GestaltOrgRepository.as(o)} on ${o.fqon} = ${fqon} and ${a.serviceOrgId} = ${o.id}
    """.map(GestaltAppRepository(a)).single.apply()
  }

  def findByAppId(appId: UUID): Option[GestaltAppRepository] = GestaltAppRepository.find(appId)

  def listByOrgId(orgId: UUID): List[GestaltAppRepository] = {
    GestaltAppRepository.findAllBy(sqls"org_id=${orgId}")
  }

}
