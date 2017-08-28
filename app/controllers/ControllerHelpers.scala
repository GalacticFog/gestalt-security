package controllers

import com.galacticfog.gestalt.security.api.errors._
import com.galacticfog.gestalt.security.api.json.JsonImports._
import controllers.AuditEvents.FailedBadRequest
import org.postgresql.util.PSQLException
import play.api.Logger
import play.api.http.{ContentTypes, HeaderNames, HttpProtocol, Status}
import play.api.libs.json._
import play.api.mvc._

import scala.util.{Failure, Success, Try}

trait ControllerHelpers extends Results with BodyParsers with HttpProtocol with Status with HeaderNames with ContentTypes with RequestExtractors with Rendering {

  val log = Logger(this.getClass)

  def defaultBadPatch(implicit request: RequestHeader) = {
    play.api.mvc.Results.BadRequest(Json.toJson(BadRequestException(
      resource = request.path,
      message = "PATCH payload contained unsupported fields",
      developerMessage = "The PATCH payload did not match the semantics of the resource"
    )))
  }

  def defaultResourceNotFound(implicit request: RequestHeader) = {
    play.api.mvc.Results.NotFound(Json.toJson(ResourceNotFoundException(
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
        play.api.mvc.Results.InternalServerError(Json.toJson(UnknownAPIException(
          code = 500,
          resource = request.path,
          message = "database exception",
          developerMessage = "Caught PSQLException; see gestalt-security logs for more details."
        )))
      case oauthErr: OAuthError => play.api.mvc.Results.BadRequest(Json.toJson(oauthErr).as[JsObject] ++ Json.obj("resource" -> resource))
      case notFound: ResourceNotFoundException => play.api.mvc.Results.NotFound(Json.toJson(notFound.copy(resource = resource)))
      case badRequest: BadRequestException => play.api.mvc.Results.BadRequest(Json.toJson(badRequest.copy(resource = resource)))
      case noauthc: UnauthorizedAPIException => play.api.mvc.Results.Unauthorized(Json.toJson(noauthc.copy(resource = resource)))
      case noauthz: ForbiddenAPIException => play.api.mvc.Results.Forbidden(Json.toJson(noauthz))
      case conflict: ConflictException => play.api.mvc.Results.Conflict(Json.toJson(conflict.copy(resource = resource)))
      case unknown: UnknownAPIException => play.api.mvc.Results.BadRequest(Json.toJson(unknown.copy(resource = resource))) // not sure why this would happen, but if we have that level of info, might as well use it
      case nope: Throwable =>
        log.error("caught unexpected error", nope)
        play.api.mvc.Results.InternalServerError(Json.toJson(UnknownAPIException(
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

  def withBody[T](auditer: Auditer, fef: FailedEventFactory)(block: T => Result)(implicit request: Request[JsValue], m: reflect.Manifest[T], rds: Reads[T]): Result = {
    request.body.validate[T] match {
      case JsSuccess(b, _) => block(b)
      case _: JsError =>
        auditer(FailedBadRequest(fef.failed, Some("payload could not be parsed")))
        handleError(request, BadRequestException(
          resource = request.path,
          message = "invalid payload",
          developerMessage = s"Payload could not be parsed; was expecting JSON representation of SDK object ${m.toString}"
        ))
    }
  }

}
