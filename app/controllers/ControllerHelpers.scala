package controllers

import java.util.UUID

import com.galacticfog.gestalt.security.api.errors._
import com.galacticfog.gestalt.security.api.json.JsonImports._
import com.galacticfog.gestalt.security.data.domain.OrgFactory.Rights
import com.galacticfog.gestalt.security.data.domain.RightGrantFactory
import controllers.AuditEvents.{Failed403, FailedBadRequest}
import org.postgresql.util.PSQLException
import play.api.Logger
import play.api.http.{ContentTypes, HeaderNames, HttpProtocol, Status}
import play.api.libs.json._
import play.api.mvc._
import AuditEvents._
import com.galacticfog.gestalt.security.api.GestaltResource

import scala.util.{Failure, Success, Try}

trait ControllerHelpers extends Results with BodyParsers with HttpProtocol with Status with HeaderNames with ContentTypes with RequestExtractors with Rendering {
  this: WithAuditer =>

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
                 a2b: A => B) = bodyTry match {
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

  def withBody[T](fef: AuditEventFactory[_])(block: T => Result)(implicit request: Request[JsValue], m: reflect.Manifest[T], rds: Reads[T]): Result = {
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

  def withBody[T,U](fef: AuditEventFactory[_])(block: Either[T,U] => Result)(implicit request: Request[JsValue], m: reflect.Manifest[T], n: reflect.Manifest[U], rdsT: Reads[T], rdsU: Reads[U]): Result = {
    request.body.validate[T] match {
      case JsSuccess(t, _) => block(Left(t))
      case _: JsError => request.body.validate[U] match {
        case JsSuccess(u, _) => block(Right(u))
        case _: JsError =>
          auditer(FailedBadRequest(fef.failed, Some("payload could not be parsed")))
          handleError(request, BadRequestException(
            resource = request.path,
            message = "invalid payload",
            developerMessage = s"Payload could not be parsed; was expecting JSON representation of SDK object ${m.toString} or ${n.toString}"
          ))
      }
    }
  }

  def withAuthorization[T,E <: AuditEvent]( requiredRight: String, ef: AuditEventFactory[E] )
                                          ( block: E => Security.AuthenticatedRequest[T, GestaltHeaderAuthentication.AccountWithOrgContext] => Result )
                                          ( request: Security.AuthenticatedRequest[T, GestaltHeaderAuthentication.AccountWithOrgContext] ): Result = {
    withAuthorization(_ => Some(requiredRight),ef)(block)(request)
  }

  def withAuthorization[T,E <: AuditEvent]( requiredRight: Security.AuthenticatedRequest[_, GestaltHeaderAuthentication.AccountWithOrgContext] => Option[String], ef: AuditEventFactory[E] )
                                          ( block: E => Security.AuthenticatedRequest[T, GestaltHeaderAuthentication.AccountWithOrgContext] => Result )
                                          ( request: Security.AuthenticatedRequest[T, GestaltHeaderAuthentication.AccountWithOrgContext] ): Result = {
    val authedEvent = ef.authed(request.user.identity)
    requiredRight(request) match {
      case Some(requiredRight) =>
        val rights = RightGrantFactory.listAccountRights(appId = request.user.serviceAppId, accountId = request.user.identity.id.asInstanceOf[UUID]).toSet
        if (!rights.exists(r => (requiredRight == r.grantName || r.grantName == Rights.SUPERUSER) && r.grantValue.isEmpty)) {
          auditer(Failed403(authedEvent,requiredRight,request))(request)
          handleError(request, ForbiddenAPIException(
            message = "Forbidden",
            developerMessage = "Forbidden. API credentials did not correspond to the parent organization or the account did not have sufficient permissions."
          ))
        } else {
          block(authedEvent)(request)
        }
      case None =>
        block(authedEvent)(request)
    }
  }

  def auditTry[T,E <: AuditEvent](theTry: Try[T], e: E)( success: (E,T) => E )(implicit request: RequestHeader): Unit = {
    theTry match {
      case Success(t)  => auditer(success(e,t))(request)
      case Failure(ex) => auditer(AuditEvents.mapExceptionToFailedEvent(ex, e))(request)
    }
  }

}
