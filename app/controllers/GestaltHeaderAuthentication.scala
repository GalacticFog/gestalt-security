package controllers

import java.util.{UUID, Base64}
import com.galacticfog.gestalt.security.api.{GestaltBearerCredentials, GestaltBasicCredentials, GestaltBasicCredsToken, GestaltAPICredentials}
import com.galacticfog.gestalt.security.api.errors.UnauthorizedAPIException
import com.galacticfog.gestalt.security.data.domain._
import com.galacticfog.gestalt.security.data.model.UserAccountRepository
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json.{Json, JsValue}
import play.api.mvc._
import play.api.mvc.Security._
import scala.concurrent.Future
import com.galacticfog.gestalt.security.api.json.JsonImports.exceptionFormat

import scala.language.reflectiveCalls

trait GestaltHeaderAuthentication {

  import GestaltHeaderAuthentication._

  class AuthenticatedActionBuilder(maybeGenFQON: Option[RequestHeader => Option[UUID]] = None) extends ActionBuilder[({ type λ[A] = play.api.mvc.Security.AuthenticatedRequest[A, AccountWithOrgContext] })#λ] {
    override def invokeBlock[B](request: Request[B], block: AuthenticatedRequest[B,AccountWithOrgContext] => Future[Result]) = {
      AuthenticatedBuilder(authenticateAgainstOrg(maybeGenFQON flatMap {
        _.apply(request)
      }), onUnauthorized = onUnauthorized).invokeBlock(request, block)
    }
  }

  object AuthenticatedAction extends AuthenticatedActionBuilder {
    def apply(genFQON: RequestHeader => Option[UUID]) = new AuthenticatedActionBuilder(Some(genFQON))
    def apply(genFQON: => Option[UUID]) = new AuthenticatedActionBuilder(Some({rh: RequestHeader => genFQON}))
  }

  def onUnauthorized(request: RequestHeader) = {
    Logger.info("rejected request from " + extractAuthToken(request).map{_.headerValue})
    Results.Unauthorized(
      Json.toJson(UnauthorizedAPIException(
        resource = request.path,
        message = "Unauthorized",
        developerMessage = "Not authenticated. Authentication credentials were missing or not valid for the resource context."
      ))
    ).withHeaders(("WWW-Authenticate","Basic"))
  }
}

object GestaltHeaderAuthentication {

  case class AccountWithOrgContext(identity: UserAccountRepository, orgId: UUID, serviceAppId: UUID)

  def extractAuthToken(request: RequestHeader): Option[GestaltAPICredentials] = {
    request.headers.get("Authorization") flatMap GestaltAPICredentials.getCredentials
  }

  def authenticateAgainstOrg(orgId: Option[UUID])(request: RequestHeader): Option[AccountWithOrgContext] = {
    val maybeCredOrToken = extractAuthToken(request) flatMap { _ match {
      case GestaltBearerCredentials(token) => for {
        foundToken <- TokenFactory.findToken(token)
        now = DateTime.now
        if orgId.contains(foundToken.issuedOrgId) && (foundToken.issuedAt isBefore now) && (now isBefore foundToken.expiresAt)
        account <- AccountFactory.find(foundToken.accountId.asInstanceOf[UUID])
        orgId = foundToken.issuedOrgId.asInstanceOf[UUID]
        serviceApp <- AppFactory.findServiceAppForOrg(orgId)
        serviceAppId = serviceApp.id.asInstanceOf[UUID]
      } yield AccountWithOrgContext(identity = account, orgId = orgId, serviceAppId = serviceAppId)
      case GestaltBasicCredentials(apiKey,apiSecret) => for {
        foundKey <- APICredentialFactory.findByAPIKey(apiKey)
        if foundKey.apiSecret == apiSecret && orgId.contains(foundKey.orgId) && !foundKey.disabled
        orgId = foundKey.orgId.asInstanceOf[UUID]
        serviceApp <- AppFactory.findServiceAppForOrg(orgId)
        serviceAppId = serviceApp.id.asInstanceOf[UUID]
        account <- UserAccountRepository.find(foundKey.accountId)
      } yield AccountWithOrgContext(identity = account, orgId = orgId, serviceAppId = serviceAppId)
    }}
    lazy val maybeAccountAuth = for {
      orgId <- orgId
      serviceApp <- AppFactory.findServiceAppForOrg(orgId)
      serviceAppId = serviceApp.id.asInstanceOf[UUID]
      account <- AccountFactory.frameworkAuth(serviceAppId, request)
    } yield AccountWithOrgContext(identity = account, orgId = orgId, serviceAppId = serviceAppId)
    // give token auth a chance first, because it doesn't require org context
    maybeCredOrToken orElse maybeAccountAuth
  }

}
