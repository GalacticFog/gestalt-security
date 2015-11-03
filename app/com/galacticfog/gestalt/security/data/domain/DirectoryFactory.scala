package com.galacticfog.gestalt.security.data.domain

import com.galacticfog.gestalt.security.api.{GestaltDirectoryCreate, GestaltPasswordCredential, GestaltAccountCreate}
import com.galacticfog.gestalt.security.api.errors.{CreateConflictException, ResourceNotFoundException}
import org.mindrot.jbcrypt.BCrypt
import scalikejdbc._
import java.util.UUID
import com.galacticfog.gestalt.security.data.model.{GestaltAppRepository, UserAccountRepository, GestaltDirectoryRepository}

object DirectoryFactory extends SQLSyntaxSupport[GestaltDirectoryRepository] {

  def createDirectory(orgId: UUID, create: GestaltDirectoryCreate): GestaltDirectoryRepository = {
    // TODO
    ???
  }

  def createAccountInDir(dirId: UUID, create: GestaltAccountCreate)(implicit session: DBSession = autoSession): UserAccountRepository = {
    // have to find the default account store for the app
    // if it's a directory, add the account to the directory
    // if it's a group, add the account to the group's directory and then to the group
    // then add the account to any groups specified in the create request
    if (GestaltDirectoryRepository.find(dirId).isEmpty) {
      throw new ResourceNotFoundException(
        resource = s"/directories/${dirId}",
        message = "could not create account in non-existent directory",
        developerMessage = "Could not create account in non-existent directory. If this error was encountered during an attempt to create an account in an org, it suggests that the org is misconfigured."
      )
    }
    if (UserAccountRepository.findBy(sqls"username = ${create.username} and dir_id = ${dirId}").isDefined) {
      throw new CreateConflictException(
        resource = s"/directories/${dirId}",
        message = "username already exists in directory",
        developerMessage = "The directory already contains an account with the specified username."
      )
    }
    if (UserAccountRepository.findBy(sqls"email = ${create.email} and dir_id = ${dirId}").isDefined) {
      throw new CreateConflictException(
        resource = s"/directories/${dirId}",
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

  def listByOrgId(orgId: UUID): List[GestaltDirectoryRepository] = {
    GestaltDirectoryRepository.findAllBy(sqls"org_id=${orgId}")
  }

}
