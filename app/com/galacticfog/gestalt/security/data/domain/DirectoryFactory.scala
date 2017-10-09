package com.galacticfog.gestalt.security.data.domain

import com.galacticfog.gestalt.security.api._
import com.galacticfog.gestalt.security.adapter.LDAPDirectory
import com.galacticfog.gestalt.security.api.errors.{BadRequestException, ConflictException, ResourceNotFoundException}
import org.postgresql.util.PSQLException
import scalikejdbc._
import scalikejdbc.TxBoundary.Try._
import java.util.UUID

import com.galacticfog.gestalt.patch.PatchOp
import com.galacticfog.gestalt.security.data.APIConversions
import com.galacticfog.gestalt.security.data.model._
import com.galacticfog.gestalt.security.plugins.DirectoryPlugin
import play.api.libs.json._

import scala.util.{Failure, Try}

trait Directory extends DirectoryPlugin {

}


object DirectoryFactory extends SQLSyntaxSupport[GestaltDirectoryRepository] {

  def sanitizeConfig(dir: GestaltDirectory): GestaltDirectory = {
    def sanitizeJson(json: JsObject): JsObject = {
      JsObject(json.fields map {
        case (key, JsString(_)) if key.toLowerCase.contains("password") =>
          key -> JsString("********")
        case (key, obj: JsObject) =>
          key -> sanitizeJson(obj)
        case (key, value) =>
          key -> value
      })
    }
    dir.copy(
      config = dir.config map {_ match {
        case obj: JsObject => sanitizeJson(obj)
        case other => other
      }}
    )
  }

  def updateDirectory(directory: GestaltDirectoryRepository, patch: Seq[PatchOp])
                     (implicit session: DBSession = autoSession): Try[GestaltDirectoryRepository] = {
    val newDir = Try{patch.foldLeft(directory)((d, p) => {
      p match {
        case PatchOp(op,"/name",Some(value)) if op.toLowerCase == "replace" =>
          d.copy(name = value.as[String])
        case PatchOp(op,"/description",Some(value)) if op.toLowerCase == "add" || op.toLowerCase == "replace" =>
          d.copy(description = Some(value.as[String]))
        case PatchOp("remove","/description",None) =>
          d.copy(description = None)
        case PatchOp(op,"/config",Some(value)) if op.toLowerCase == "add" || op.toLowerCase == "replace" =>
          d.copy(config = Some(value.toString))
        case PatchOp("remove","/config",None) =>
          d.copy(config = None)
        case _ => throw BadRequestException(
          resource = "",
          message = "bad PATCH payload for updating directory",
          developerMessage = "The PATCH payload for updating the directory had invalid fields/operations."
        )
      }
    })}
    newDir map (_.save())
  }


  implicit def toDirFromDAO(daoDir: GestaltDirectoryRepository): DirectoryPlugin = {
    daoDir.directoryType.toUpperCase match {
      case "INTERNAL" => InternalDirectory(daoDir)
      case "LDAP" =>
//        val ldapClass = Class.forName("com.galacticfog.gestalt.security.data.domain.LDAPDirectory").getConstructors.head
//        val ldapdir = ldapClass.newInstance(daoDir, daoDir.config, SDKAccountFactory.instance, SDKGroupFactory.instance).asInstanceOf[LDAPDirectory]
        val dirapi = APIConversions.dirModelToApi(daoDir)
        val ldapdir = LDAPDirectory(dirapi, daoDir.config, SDKAccountFactory.instance, SDKGroupFactory.instance)
        ldapdir
      case _ => throw BadRequestException(
        resource = s"/directories/${daoDir.id}",
        message = "invalid directory type",
        developerMessage = "The requested directory has an unsupported directory type. Please ensure that you are running the latest version and contact support."
      )
    }

  }

  override val autoSession = AutoSession

  def findRaw(dirId: UUID)(implicit session: DBSession = autoSession): Option[GestaltDirectoryRepository] = {
    GestaltDirectoryRepository.find(dirId)
  }

  def find(dirId: UUID)(implicit session: DBSession = autoSession): Option[DirectoryPlugin] = {
    findRaw(dirId) map {d => d:DirectoryPlugin}
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

  def createDirectory(orgId: UUID, create: GestaltDirectoryCreate)(implicit session: DBSession = autoSession): Try[GestaltDirectoryRepository] = {
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
      case t: PSQLException if t.getSQLState == "23505" || t.getSQLState == "23514" =>
        t.getServerErrorMessage.getConstraint match {
          case "directory_name_org_id_key" => Failure(ConflictException(
            resource = s"/orgs/${orgId}/directories",
            message = "directory with specified name already exists in org",
            developerMessage = "The org already contains a directory with the specified name."
          ))
          case _ => Failure(t)
        }
    }
  }

  def createAccountInDir(dirId: UUID, create: GestaltAccountCreate)(implicit session: DBSession = autoSession): Try[GestaltAccount] = {
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
    listRawByOrgId(orgId) map {d => d:DirectoryPlugin}
  }

  def listRawByOrgId(orgId: UUID) = {
    GestaltDirectoryRepository.findAllBy(sqls"org_id=${orgId}")
  }


}
