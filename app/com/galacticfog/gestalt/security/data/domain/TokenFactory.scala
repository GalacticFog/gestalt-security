package com.galacticfog.gestalt.security.data.domain

import java.util.UUID

import com.galacticfog.gestalt.security.api.GestaltToken.{TokenType, ACCESS_TOKEN}
import com.galacticfog.gestalt.security.api.errors.BadRequestException
import com.galacticfog.gestalt.security.data.model._
import org.joda.time.{Duration, DateTime}
import org.postgresql.util.PSQLException
import scalikejdbc._

import scala.util.{Failure, Try}

object TokenFactory extends SQLSyntaxSupport[TokenRepository] {

  override val autoSession = AutoSession

  def findToken(tokenStr: String)(implicit session: DBSession = autoSession): Option[TokenRepository] = {
    for {
      tokenUUID <- Try{UUID.fromString(tokenStr)}.toOption
      token <- TokenRepository.find(tokenUUID)
    } yield token
  }

  def createToken(orgId: UUID, accountId: UUID, validForSeconds: Long, tokenType: TokenType)(implicit session: DBSession = autoSession): Try[TokenRepository] =
  {
    val tt = tokenType match {
      case ACCESS_TOKEN => "ACCESS"
    }
    Try {
      val now = DateTime.now
      TokenRepository.create(
        id = UUID.randomUUID(),
        accountId = accountId,
        issuedAt = now,
        expiresAt = now plus Duration.standardSeconds(validForSeconds),
        refreshToken = None,
        tokenType = tt,
        issuedOrgId = orgId
      )
    } recoverWith {
      case t: PSQLException if (t.getSQLState == "23505" || t.getSQLState == "23514") =>
        t.getServerErrorMessage.getConstraint match {
          case "token_account_id_fkey" => Failure(BadRequestException(
            resource = "",
            message = "account does not exist",
            developerMessage = "The provided account ID is not valid."
          ))
          case "token_issued_org_id_fkey" => Failure(BadRequestException(
            resource = "",
            message = "org does not exist",
            developerMessage = "The provided org ID is not valid."
          ))
          case "token_refresh_token_fkey" => Failure(BadRequestException(
            resource = "",
            message = "refresh token not valid",
            developerMessage = "The provided refresh token is not valid."
          ))
          case "token_token_type_fkey" => Failure(BadRequestException(
            resource = "",
            message = "token type not valid",
            developerMessage = "The provided token type is not valid."
          ))
          case _ => Failure(t)
        }
    }
  }

}
