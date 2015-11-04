package com.galacticfog.gestalt.security.data.domain

import com.galacticfog.gestalt.security.api.{GestaltGroupCreate, GestaltDirectoryCreate, GestaltPasswordCredential, GestaltAccountCreate}
import com.galacticfog.gestalt.security.api.errors.{CreateConflictException, ResourceNotFoundException}
import org.mindrot.jbcrypt.BCrypt
import play.api.libs.json.Json
import scalikejdbc._
import java.util.UUID
import com.galacticfog.gestalt.security.data.model.{UserGroupRepository, GestaltAppRepository, UserAccountRepository, GestaltDirectoryRepository}

object DirectoryFactory extends SQLSyntaxSupport[GestaltDirectoryRepository] {


  def createDirectory(orgId: UUID, create: GestaltDirectoryCreate)(implicit session: DBSession = autoSession): GestaltDirectoryRepository = {
    if (GestaltDirectoryRepository.findBy(sqls"name = ${create.name} and org_id = ${orgId}").isDefined) {
      throw new CreateConflictException(
        resource = s"/orgs/${orgId}/directories",
        message = "directory with specified name already exists in org",
        developerMessage = "The org already contains a directory with the specified name."
      )
    }
    GestaltDirectoryRepository.create(
      id = UUID.randomUUID(),
      orgId = orgId,
      name = create.name,
      description = create.description,
      config = create.config map {_.toString}
    )
  }

  def createAccountInDir(dirId: UUID, create: GestaltAccountCreate)(implicit session: DBSession = autoSession): UserAccountRepository = {
    if (GestaltDirectoryRepository.find(dirId).isEmpty) {
      throw new ResourceNotFoundException(
        resource = s"/directories/${dirId}",
        message = "could not create account in non-existent directory",
        developerMessage = "Could not create account in non-existent directory. If this error was encountered during an attempt to create an account in an org, it suggests that the org is misconfigured."
      )
    }
    if (UserAccountRepository.findBy(sqls"username = ${create.username} and dir_id = ${dirId}").isDefined) {
      throw new CreateConflictException(
        resource = s"/directories/${dirId}/accounts",
        message = "username already exists in directory",
        developerMessage = "The directory already contains an account with the specified username."
      )
    }
    if (UserAccountRepository.findBy(sqls"email = ${create.email} and dir_id = ${dirId}").isDefined) {
      throw new CreateConflictException(
        resource = s"/directories/${dirId}/accounts",
        message = "email address already exists in directory",
        developerMessage = "The directory already contains an account with the specified email address."
      )
    }
    val cred = create.credential.asInstanceOf[GestaltPasswordCredential]
    UserAccountRepository.create(
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
    )
  }

  def createGroupInDir(dirId: UUID, create: GestaltGroupCreate)(implicit session: DBSession = autoSession): UserGroupRepository = {
    if (GestaltDirectoryRepository.find(dirId).isEmpty) {
      throw new ResourceNotFoundException(
        resource = s"/directories/${dirId}",
        message = "could not create group in non-existent directory",
        developerMessage = "Could not create group in non-existent directory. If this error was encountered during an attempt to create a group in an org, it suggests that the org is misconfigured."
      )
    }
    if (UserGroupRepository.findBy(sqls"name = ${create.name} and dir_id = ${dirId}").isDefined) {
      throw new CreateConflictException(
        resource = s"/directories/${dirId}/groups",
        message = "group name already exists in directory",
        developerMessage = "The directory already contains a group with the specified name."
      )
    }
    UserGroupRepository.create(
      id = UUID.randomUUID(),
      dirId = dirId,
      name = create.name,
      disabled = false
    )
  }

  def listByOrgId(orgId: UUID): List[GestaltDirectoryRepository] = {
    GestaltDirectoryRepository.findAllBy(sqls"org_id=${orgId}")
  }

}
