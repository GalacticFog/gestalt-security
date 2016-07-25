package com.galacticfog.gestalt.security.data.domain

import com.galacticfog.gestalt.security.api._
import com.galacticfog.gestalt.security.adapter.LDAPDirectory
import com.galacticfog.gestalt.security.api.errors.{BadRequestException, ConflictException, ResourceNotFoundException}
import org.postgresql.util.PSQLException
import scalikejdbc._
import scalikejdbc.TxBoundary.Try._
import java.util.UUID

import com.galacticfog.gestalt.security.data.model._
import com.galacticfog.gestalt.security.plugins.DirectoryPlugin

import scala.util.{Failure, Try}

trait Directory extends DirectoryPlugin {

}


object DirectoryFactory extends SQLSyntaxSupport[GestaltDirectoryRepository] {

  implicit def toDirFromDAO(daoDir: GestaltDirectoryRepository): DirectoryPlugin = {
    daoDir.directoryType.toUpperCase match {
      case "INTERNAL" => InternalDirectory(daoDir)
      case "LDAP" =>
//        val ldapClass = Class.forName("com.galacticfog.gestalt.security.data.domain.LDAPDirectory").getConstructors.head
//        val ldapdir = ldapClass.newInstance(daoDir, daoDir.config, SDKAccountFactory.instance, SDKGroupFactory.instance).asInstanceOf[LDAPDirectory]
        val ldapdir = LDAPDirectory(daoDir.asInstanceOf[GestaltDirectory], daoDir.config, SDKAccountFactory.instance, SDKGroupFactory.instance)
        ldapdir
      case _ => throw new BadRequestException(
        resource = s"/directories/${daoDir.id}",
        message = "invalid directory type",
        developerMessage = "The requested directory has an unsupported directory type. Please ensure that you are running the latest version and contact support."
      )
    }

  }

  override val autoSession = AutoSession

  def find(dirId: UUID)(implicit session: DBSession = autoSession): Option[DirectoryPlugin] = {
    GestaltDirectoryRepository.find(dirId) map {d => d:DirectoryPlugin}
  }

  def findAll(implicit session: DBSession = autoSession): List[DirectoryPlugin] = {
    GestaltDirectoryRepository.findAll map { d => d: DirectoryPlugin }
  }

  def removeDirectory(dirId: UUID)(implicit session: DBSession = autoSession): Unit = {
    GestaltDirectoryRepository.find(dirId) match {
      case Some(dir) => dir.destroy()
      case _ =>
    }
  }

  def createDirectory(orgId: UUID, create: GestaltDirectoryCreate)(implicit session: DBSession = autoSession): Try[DirectoryPlugin] = {
	println(s"config = ${create.config}")
    Try {
      GestaltDirectoryRepository.create(
        id = UUID.randomUUID(),
        orgId = orgId,
        name = create.name,
        description = create.description,
        config = create.config map {_.toString},
        directoryType = create.directoryType.label
      )
    } recoverWith {
      case t: PSQLException if (t.getSQLState == "23505" || t.getSQLState == "23514") =>
        t.getServerErrorMessage.getConstraint match {
          case "directory_name_org_id_key" => Failure(ConflictException(
            resource = s"/orgs/${orgId}/directories",
            message = "directory with specified name already exists in org",
            developerMessage = "The org already contains a directory with the specified name."
          ))
          case _ => Failure(t)
        }
    } map {d => d: DirectoryPlugin}
  }

  def createAccountInDir(dirId: UUID, create: GestaltAccountCreate)(implicit session: DBSession = autoSession): Try[GestaltAccount] = {
    println("DirectoryFactory.createAccountInDir()....")
    DirectoryFactory.find(dirId) match {
      case None => Failure(ResourceNotFoundException(
        resource = s"/directories/${dirId}",
        message = "could not create account in non-existent directory",
        developerMessage = "Could not create account in non-existent directory. If this error was encountered during an attempt to create an account in an org, it suggests that the org is misconfigured."
      ))
      case Some(dir) =>
        DB localTx { implicit session =>
          for {
            cred <- Try(create.credential.asInstanceOf[GestaltPasswordCredential])
            newAccount <- dir.createAccount(
              username = create.username,
              description = create.description,
              email = create.email.map(_.trim).filter(!_.isEmpty),
              phoneNumber = create.phoneNumber.map(_.trim).filter(!_.isEmpty),
              firstName = create.firstName,
              lastName = create.lastName,
              cred = cred
            )
            _ = create.groups.toSeq.flatten foreach {
              grpId => GroupMembershipRepository.create(
                accountId = newAccount.id.asInstanceOf[UUID],
                groupId = grpId
              )
            }
          } yield newAccount
        }
    }
  }

  def createGroupInDir(dirId: UUID, create: GestaltGroupCreate)(implicit session: DBSession = autoSession): Try[GestaltGroup] = {
    GestaltDirectoryRepository.find(dirId) match {
      case None => Failure(ResourceNotFoundException(
        resource = s"/directories/${dirId}",
        message = "could not create group in non-existent directory",
        developerMessage = "Could not create group in non-existent directory. If this error was encountered during an attempt to create a group in an org, it suggests that the org is misconfigured."
      ))
      case Some(daodir) => daodir.createGroup(
        name = create.name,
        description = create.description
      )
    }
  }

  def listByOrgId(orgId: UUID)(implicit session: DBSession = autoSession): List[DirectoryPlugin] = {
    GestaltDirectoryRepository.findAllBy(sqls"org_id=${orgId}") map {d => d:DirectoryPlugin}
  }

}
