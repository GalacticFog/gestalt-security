package controllers

import java.util.UUID

import com.galacticfog.gestalt.io.util.PatchOp
import com.galacticfog.gestalt.security.Global
import com.galacticfog.gestalt.security.api.AccessTokenResponse.BEARER
import com.galacticfog.gestalt.security.api.GestaltToken.ACCESS_TOKEN
import com.galacticfog.gestalt.security.api._
import com.galacticfog.gestalt.security.api.errors._
import com.galacticfog.gestalt.security.api.json.JsonImports._
import com.galacticfog.gestalt.security.data.APIConversions._
import com.galacticfog.gestalt.security.data.domain.OrgFactory.Rights._
import com.galacticfog.gestalt.security.data.domain._
import com.galacticfog.gestalt.security.data.model._
import org.joda.time.Duration
import play.api._
import play.api.http.MimeTypes
import play.api.libs.json._
import play.api.mvc._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

trait ControllerHelpers extends Controller with GestaltHeaderAuthentication {
  this : Controller =>

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

  case class TryRenderer[B](status: Status) {
    def apply[A](bodyTry: Try[A])
                (implicit request: RequestHeader,
                 tjs : play.api.libs.json.Writes[B],
                 c: A => B) = bodyTry match {
      case Success(body) =>
        status(Json.toJson(body: B))
      case Failure(e) =>
        Global.handleError(request, e)
    }
  }

  def renderTry[B](status: Status) = TryRenderer[B](status)

  def validateBody[T](implicit request: Request[JsValue], m: reflect.Manifest[T], rds: Reads[T]): T = {
    request.body.validate[T] match {
      case s: JsSuccess[T] => s.get
      case e: JsError => throw new BadRequestException(
        resource = request.path,
        message = "invalid payload",
        developerMessage = s"Payload could not be parsed; was expecting JSON representation of SDK object ${m.toString}"
      )
    }
  }

}
