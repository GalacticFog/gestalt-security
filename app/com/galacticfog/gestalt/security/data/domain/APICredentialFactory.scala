package com.galacticfog.gestalt.security.data.domain

import java.util.UUID

import com.galacticfog.gestalt.security.api.errors.BadRequestException
import com.galacticfog.gestalt.security.data.model.{TokenRepository, APICredentialRepository}
import com.galacticfog.gestalt.security.utils.SecureIdGenerator
import org.postgresql.util.PSQLException
import play.api.Logger
import scalikejdbc._

import scala.util.{Failure, Try}

object APICredentialFactory extends SQLSyntaxSupport[APICredentialRepository] {

  override val autoSession = AutoSession

  def findByAPIKey(apiKey: String)(implicit session: DBSession = autoSession): Option[APICredentialRepository] = {
    Try{UUID.fromString(apiKey)}.toOption flatMap APICredentialRepository.find
  }

  def createAPIKey(accountId: UUID,
                   boundOrg: Option[UUID],
                   parentApiKey: Option[APICredentialRepository])
                  (implicit session: DBSession = autoSession): Try[APICredentialRepository] = {
    Try {
      APICredentialRepository.create(
        apiKey = UUID.randomUUID(),
        apiSecret = SecureIdGenerator.genId64(40),
        accountId = accountId,
        issuedOrgId = boundOrg,
        disabled = false,
        parentApikey = parentApiKey.map(_.apiKey.asInstanceOf[UUID])
      )
    } recoverWith {
      case t: PSQLException if (t.getSQLState == "23505" || t.getSQLState == "23503") =>
        t.getServerErrorMessage.getConstraint match {
          case "api_credential_org_id_fkey" => Failure(BadRequestException(
            resource = "",
            message = "account is not a member of the specified org",
            developerMessage = "The account is not a member of the specified org or the org ID does not exist."
          ))
          case _ => Failure(t)
        }
    }
  }

}
