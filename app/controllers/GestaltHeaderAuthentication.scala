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
      AuthenticatedBuilder(authenticateHeader, onUnauthorized = onUnauthorized).invokeBlock(request, block)
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

  // find the account by credentials and verify that they are still part of the associated app
  def authenticateHeader(request: RequestHeader): Option[AccountWithOrgContext] = {
    // TODO: add more debugging
    for {
      tokenHeader <- extractAuthToken(request)
      apiCred <- tokenHeader match {
        case GestaltBearerCredentials(token) =>
          Logger.info("found Bearer credential, will attempt to validate as token")
          TokenFactory.findValidToken(token) map Right.apply
        case GestaltBasicCredentials(apiKey,apiSecret) =>
          Logger.info("found Basic credential, will attempt to validate as apiKey")
          APICredentialFactory.findByAPIKey(apiKey) filter (found => found.apiSecret == apiSecret && found.disabled == false) map Left.apply
      }
      orgId = apiCred.fold(_.orgId.asInstanceOf[UUID], _.issuedOrgId.asInstanceOf[UUID])
      serviceApp <- AppFactory.findServiceAppForOrg(orgId)
      serviceAppId = serviceApp.id.asInstanceOf[UUID]
      account <- AccountFactory.getAppAccount(serviceAppId, apiCred.fold(_.accountId,_.accountId).asInstanceOf[UUID])
    } yield AccountWithOrgContext(identity = account, orgId = orgId, serviceAppId = serviceAppId)
  }

}
