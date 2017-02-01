package controllers

import javax.inject._

import com.galacticfog.gestalt.security.api.errors.{BadRequestException, ResourceNotFoundException}
import play.api.http.DefaultHttpErrorHandler
import play.api._
import play.api.libs.json.Json
import play.api.mvc._
import play.api.mvc.Results._
import play.api.routing.Router
import com.galacticfog.gestalt.security.api.json.JsonImports._

import scala.concurrent._

@Singleton
class SDKAwareErrorHandler @Inject()(env: Environment,
                                     config: Configuration,
                                     sourceMapper: OptionalSourceMapper,
                                     router: Provider[Router] )
  extends DefaultHttpErrorHandler(env, config, sourceMapper, router) with ControllerHelpers {

  override def onServerError(request: RequestHeader, exception: Throwable): Future[Result] = {
    log.error("Global::onError", exception)
    Future.successful(handleError(request,exception))
  }

  override def onClientError(request: RequestHeader, statusCode: Int, message: String): Future[Result] = {
    log.info(s"HttpErrorHandler::onClientError: ${statusCode} @ ${request.path}")
    statusCode match {
      case play.api.http.Status.BAD_REQUEST =>
        Future.successful(BadRequest(Json.toJson(BadRequestException(
          resource = request.path,
          message = s"bad request: ${message}",
          developerMessage = s"Bad request: ${message}"
        ))))
      case play.api.http.Status.NOT_FOUND =>
        Future.successful(NotFound(Json.toJson(ResourceNotFoundException(
          resource = request.path,
          message = s"resource/endpoint not found",
          developerMessage = s"Resource/endpoint not found after authentication."
        ))))
      case _ =>
        super.onClientError(request, statusCode, message)
    }
  }
}
