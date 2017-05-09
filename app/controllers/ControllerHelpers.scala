package controllers

import com.galacticfog.gestalt.security.api.errors._
import com.galacticfog.gestalt.security.api.json.JsonImports._
import org.postgresql.util.PSQLException
import play.api.Logger
import play.api.libs.json._
import play.api.mvc._

import scala.util.{Failure, Success, Try}

trait ControllerHelpers extends Controller with GestaltHeaderAuthentication {
  this : Controller =>

  val log = Logger(this.getClass)

  def defaultBadPatch(implicit request: RequestHeader) = {
    BadRequest(Json.toJson(BadRequestException(
      resource = request.path,
      message = "PATCH payload contained unsupported fields",
      developerMessage = "The PATCH payload did not match the semantics of the resource"
    )))
  }

  def defaultResourceNotFound(implicit request: RequestHeader) = {
    NotFound(Json.toJson(ResourceNotFoundException(
      resource = request.path,
      message = "resource not found",
      developerMessage = "Resource not found."
    )))
  }

  def handleError(request: RequestHeader, e: Throwable): Result = {
    val resource = e match {
      case sre: SecurityRESTException if !sre.resource.isEmpty =>
        sre.resource
      case _ => request.path
    }
    e match {
      case sql: PSQLException =>
        log.error(s"caught psql error with state ${sql.getSQLState}", sql)
        InternalServerError(Json.toJson(UnknownAPIException(
          code = 500,
          resource = request.path,
          message = "database exception",
          developerMessage = "Caught PSQLException; see gestalt-security logs for more details."
        )))
      case oauthErr: OAuthError => BadRequest(Json.toJson(oauthErr).as[JsObject] ++ Json.obj("resource" -> resource))
      case notFound: ResourceNotFoundException => NotFound(Json.toJson(notFound.copy(resource = resource)))
      case badRequest: BadRequestException => BadRequest(Json.toJson(badRequest.copy(resource = resource)))
      case noauthc: UnauthorizedAPIException => Unauthorized(Json.toJson(noauthc.copy(resource = resource)))
      case noauthz: ForbiddenAPIException => Forbidden(Json.toJson(noauthz))
      case conflict: ConflictException => Conflict(Json.toJson(conflict.copy(resource = resource)))
      case unknown: UnknownAPIException => BadRequest(Json.toJson(unknown.copy(resource = resource))) // not sure why this would happen, but if we have that level of info, might as well use it
      case nope: Throwable =>
        log.error("caught unexpected error", nope)
        InternalServerError(Json.toJson(UnknownAPIException(
          code = 500, resource = request.path, message = "internal server error", developerMessage = "Internal server error. Please check the log for more details."
        )))
    }
  }

  case class TryRenderer[B](status: Status) {
    def apply[A](bodyTry: Try[A])
                (implicit request: RequestHeader,
                 tjs : play.api.libs.json.Writes[B],
                 c: A => B) = bodyTry match {
      case Success(body) =>
        status(Json.toJson(body: B))
      case Failure(e) =>
        handleError(request, e)
    }
  }

  def renderTry[B](status: Status) = TryRenderer[B](status)

  def validateBody[T](implicit request: Request[JsValue], m: reflect.Manifest[T], rds: Reads[T]): T = {
    request.body.validate[T] match {
      case s: JsSuccess[T] => s.get
      case _: JsError => throw BadRequestException(
        resource = request.path,
        message = "invalid payload",
        developerMessage = s"Payload could not be parsed; was expecting JSON representation of SDK object ${m.toString}"
      )
    }
  }

}
