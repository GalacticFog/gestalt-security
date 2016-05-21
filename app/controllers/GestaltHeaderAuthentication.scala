package controllers

import java.util.{UUID, Base64}
import com.galacticfog.gestalt.security.api.{GestaltBearerCredentials, GestaltBasicCredentials, GestaltBasicCredsToken, GestaltAPICredentials}
import com.galacticfog.gestalt.security.api.errors.{ResourceNotFoundException, UnauthorizedAPIException}
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
      def checkingBlock: (AuthenticatedRequest[B, AccountWithOrgContext]) => Future[Result] = { request =>
        maybeGenFQON flatMap {_.apply(request)} match {
          case Some(orgId) =>
            // controller specified an orgId, so we will enforce that the account belongs to the specified org
            val serviceAppId = for {
              serviceApp <- AppFactory.findServiceAppForOrg(orgId)
              serviceAppId = serviceApp.id.asInstanceOf[UUID]
              orgAccount <- AccountFactory.getAppAccount(serviceAppId, request.user.identity.id.asInstanceOf[UUID])
            } yield serviceAppId
            serviceAppId match {
              case Some(srvAppId) =>
                // authenticated in the requested app, proceed with the user block but using the appropriate login org
                Logger.info(s"authenticated /accounts/${request.user.identity.id} against ${orgId}")
                block(new AuthenticatedRequest[B,AccountWithOrgContext](
                  request.user.copy(
                    orgId = orgId,
                    serviceAppId = srvAppId
                  ), request
                ))
              case None =>
                // did not authenticate with the requested app, we could 403 or 404, we will 403
                Logger.info(s"authenticated /accounts/${request.user.identity.id} did not belong to /orgs/${orgId}")
                throw UnauthorizedAPIException("", message = "insufficient permissions", developerMessage = "Insufficient permissions in the authenticated account to perform the requested action.")
            }
          case None =>
            // controller didn't specify an orgId, so we don't enforce that the account belongs to an orgId. just return auth information from the token.
            Logger.info(s"authenticated /accounts/${request.user.identity.id} against ${request.user.orgId}")
            block(request)
        }
      }
      AuthenticatedBuilder(authenticateHeader, onUnauthorized = onUnauthorized).invokeBlock(request, checkingBlock)
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
    val authToken = extractAuthToken(request)
    for {
      tokenHeader <- authToken
      apiCred <- tokenHeader match {
        case GestaltBearerCredentials(token) =>
          TokenFactory.findValidToken(token) map Right.apply
        case GestaltBasicCredentials(apiKey,apiSecret) =>
          APICredentialFactory.findByAPIKey(apiKey) filter (found => found.apiSecret == apiSecret && found.disabled == false) map Left.apply
      }
      orgId = apiCred.fold(_.orgId.asInstanceOf[UUID], _.issuedOrgId.asInstanceOf[UUID])
      serviceApp <- AppFactory.findServiceAppForOrg(orgId)
      serviceAppId = serviceApp.id.asInstanceOf[UUID]
      account <- AccountFactory.getAppAccount(serviceAppId, apiCred.fold(_.accountId,_.accountId).asInstanceOf[UUID])
    } yield AccountWithOrgContext(identity = account, orgId = orgId, serviceAppId = serviceAppId)
  }

}
