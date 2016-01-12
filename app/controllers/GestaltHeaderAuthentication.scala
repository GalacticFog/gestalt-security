package controllers

import java.util.{UUID, Base64}
import com.galacticfog.gestalt.security.api.errors.UnauthorizedAPIException
import com.galacticfog.gestalt.security.data.domain.{OrgFactory, AccountFactory, AppFactory, APICredentialFactory}
import com.galacticfog.gestalt.security.data.model.UserAccountRepository
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
    Logger.info("rejected request from " + extractAuthToken(request).map{_.username})
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

  sealed trait AuthenticationToken {
    def getPrincipal: String
    def getCredentials: Object
  }
  case class UsernamePasswordToken(username: String, password: String) extends AuthenticationToken {
    def getPrincipal: String = username
    def getCredentials: String = password
  }

  val AUTH_SCHEME = """(\S+) (.+)""".r
  val BASIC_SCHEME_HEADER = """Basic (\S+)""".r
  val BASIC_SCHEME_CREDS  = """([^:]+):([^:]*)""".r

  def extractAuthToken(request: RequestHeader): Option[UsernamePasswordToken] = request.headers.get("Authorization") flatMap { auth =>
    auth match {
      case BASIC_SCHEME_HEADER(encodedCredentials) => try {
        val decoded = new String(Base64.getDecoder.decode(encodedCredentials))
        decoded match {
          case BASIC_SCHEME_CREDS(u, p) => Some(UsernamePasswordToken(u, p))
          case _ => None
        }
      } catch {
        case e: Throwable => {
          Logger.error("error decoding BasicAuth credentials",e)
          None
        }
      }
      case AUTH_SCHEME(schemeName,data) =>
        Logger.info("Received unknown authentication scheme: " + schemeName)
        None
      case _ => None
    }
  }

  def authenticateAgainstOrg(orgId: Option[UUID])(request: RequestHeader): Option[AccountWithOrgContext] = {
    lazy val maybeTokenAuth = for {
      token <- extractAuthToken(request)
      foundKey <- APICredentialFactory.findByAPIKey(token.username)
      if foundKey.apiSecret == token.password && orgId.contains(foundKey.orgId) && !foundKey.disabled
      orgId = foundKey.orgId.asInstanceOf[UUID]
      serviceApp <- AppFactory.findServiceAppForOrg(orgId)
      serviceAppId = serviceApp.id.asInstanceOf[UUID]
      account <- UserAccountRepository.find(foundKey.accountId)
    } yield AccountWithOrgContext(identity = account, orgId = orgId, serviceAppId = serviceAppId)
    lazy val maybeAccountAuth = for {
      orgId <- orgId
      serviceApp <- AppFactory.findServiceAppForOrg(orgId)
      serviceAppId = serviceApp.id.asInstanceOf[UUID]
      account <- AccountFactory.frameworkAuth(serviceAppId, request)
    } yield AccountWithOrgContext(identity = account, orgId = orgId, serviceAppId = serviceAppId)
    // give token auth a chance first, because it doesn't require org context
    maybeTokenAuth orElse maybeAccountAuth
  }

}
