package controllers

import java.util.Base64
import com.galacticfog.gestalt.security.data.domain.APIAccountFactory
import com.galacticfog.gestalt.security.data.model.APIAccountRepository
import org.apache.shiro.authc.{AuthenticationToken, UsernamePasswordToken}
import play.api.Logger
import play.api.mvc._
import play.api.mvc.Security._

import scala.concurrent.{ExecutionContext, Future}

trait GestaltHeaderAuthentication {

  val BASIC_SCHEME_HEADER = """Basic (\S+)""".r
  val BASIC_SCHEME_CREDS  = """([^:]+):([^:]*)""".r

  def authToken(request: RequestHeader): Option[AuthenticationToken] = request.headers.get("Authorization") flatMap { auth =>
    auth match {
      case BASIC_SCHEME_HEADER(encodedCredentials) => try {
        val decoded = new String(Base64.getDecoder.decode(encodedCredentials))
        decoded match {
          case BASIC_SCHEME_CREDS(u, p) => Some(new UsernamePasswordToken(u, p))
          case _ => None
        }
      } catch {
        case e: Throwable => {
          Logger.error("error decoding BasicAuth credentials",e)
          None
        }
      }
      case _ => None
    }
  }

  def matches(apiKey: APIAccountRepository, token: AuthenticationToken): Boolean = {
    val t = token.getCredentials match {
      case bytes: Array[Byte] => new String(bytes)
      case chars: Array[Char] => new String(chars)
      case str: String => str
      case _ => throw new RuntimeException("Unsupported credential type")
    }
    apiKey.apiSecret equals t
  }

  def authenticate(request: RequestHeader): Option[APIAccountRepository] = {
    for {
      token <- authToken(request)
      found <- APIAccountFactory.findByAPIKey(token.getPrincipal.toString)
      if matches(found, token)
    } yield found
  }

  def onUnauthorized(request: RequestHeader) = Results.Unauthorized("").withHeaders(("WWW-Authenticate","Basic"))
  def requireAPIKeyAuthentication(f: => APIAccountRepository => Request[AnyContent] => Result)(implicit ec: ExecutionContext) = {
    Authenticated(authenticate, onUnauthorized) { user =>
      Logger.info("authenticated request from " + user.apiKey)
      Action(request => f(user)(request))
    }
  }

}
