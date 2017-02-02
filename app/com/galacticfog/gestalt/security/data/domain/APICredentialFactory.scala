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
                   parentApiKey: Option[UUID])
                  (implicit session: DBSession = autoSession): Try[APICredentialRepository] = {
    val newKey = boundOrg match {
      case None => Try{APICredentialRepository.create(
        apiKey = UUID.randomUUID(),
        apiSecret = SecureIdGenerator.genId64(40),
        accountId = accountId,
        issuedOrgId = None,
        disabled = false,
        parentApikey = parentApiKey
      )}
      case Some(orgId) =>
        val isMember = AppFactory.findServiceAppForOrg(orgId).exists(
          serviceApp => AccountFactory.getAppAccount(
            appId = serviceApp.id.asInstanceOf[UUID],
            accountId = accountId
          ).isDefined
        )
        if (isMember) Try{APICredentialRepository.create(
          apiKey = UUID.randomUUID(),
          apiSecret = SecureIdGenerator.genId64(40),
          accountId = accountId,
          issuedOrgId = Some(orgId),
          disabled = false,
          parentApikey = parentApiKey
        )}
        else Failure(BadRequestException(
          "", "account does not belong to specified org",
          "API key for the account cannot be bound to the specified org because the account does not belong to the org."
        ))
    }
    newKey recoverWith {
      case t: PSQLException if t.getSQLState == "23505" || t.getSQLState == "23503" =>
        t.getServerErrorMessage.getConstraint match {
          case "api_credential_org_id_fkey" => Failure(BadRequestException(
            resource = "",
            message = "org does not exist",
            developerMessage = "The bound org ID does not exist."
          ))
          case _ => Failure(t)
        }
    }
  }

  def findByAccountId(accountId: UUID)(implicit session: DBSession = autoSession): List[APICredentialRepository] = {
    APICredentialRepository.findAllBy(sqls"account_id = ${accountId}")
  }


}
