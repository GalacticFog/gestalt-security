package com.galacticfog.gestalt.security

import com.galacticfog.gestalt.security.actors.RateLimitingActor
import controllers.{GestaltHeaderAuthentication, RESTAPIController, InitController}
import org.postgresql.util.PSQLException
import play.api.{Application, GlobalSettings, Logger => log, Play}
import play.libs.Akka
import scala.concurrent.Future
import com.galacticfog.gestalt.security.api.errors._
import com.galacticfog.gestalt.security.data.SecurityServices
import com.galacticfog.gestalt.security.data.config.ScalikePostgresDBConnection
import com.galacticfog.gestalt.security.data.domain.{OrgFactory, DefaultAccountStoreMappingServiceImpl}
import play.api._
import play.api.Play.current
import play.api.libs.json.{JsObject, Json}
import play.api.mvc.{Action, Handler, Result, RequestHeader}
import play.api.mvc.Results._
import scalikejdbc._

object Global extends GlobalSettings with GlobalWithMethodOverriding {

  import com.galacticfog.gestalt.security.api.json.JsonImports._

  /**
   * Indicates the query parameter name used to override the HTTP method
    *
    * @return a non-empty string indicating the query parameter. Popular choice is "_method"
   */
  override def overrideParameter: String = "_method"

  val registeredEndpoints = Set(
    "accessTokens",
    "accountStores",
    "accounts",
    "apiKeys",
    "apps",
    "assets",
    "auth",
    "directories",
    "groups",
    "health",
    "info",
    "init",
    "oauth",
    "orgs",
    "rights",
    "sync"
  )

  private[this] def topLevelEndpoint(path: String): (String,String) = {
    path.split("/",3) match {
      case Array("", top)       => (top,"")
      case Array("", top, tail) => (top,"/" + tail)
      case _ => ("","")
    }
  }

  override def onRouteRequest(request: RequestHeader): Option[Handler] = {
    log.debug(request.toString)
    (request.method, request.path) match {
      case ("GET", "/init") => Some(InitController.checkInit)
      case ("POST", "/init") => Some(InitController.initialize())
      case ("GET", "/health") => Some(RESTAPIController.getHealth)
      case ("GET", "/info") => Some(RESTAPIController.info)
      case (_,_) if !Init.isInit => Some(Action { BadRequest(Json.toJson(BadRequestException(
        request.path,
        message = "service it not initialized",
        developerMessage = "The service has not been initialized. See the documentation on how to perform initialization."
      )))})
      case (_, path) => {
        val (top,tail) = topLevelEndpoint(path)
        if (registeredEndpoints.contains(top)) super.onRouteRequest(request)
        else OrgFactory.findByFQON(top) match {
          case Some(org) =>
            super.onRouteRequest(request.copy(path = s"/orgs/${org.id}${tail}"))
          case None =>
            Logger.debug(s"top level path not mappable as fqon: ${top}")
            GestaltHeaderAuthentication.authenticateHeader(request) match {
              case Some(_) => None // default 404
              case None => Some(Action { GestaltHeaderAuthentication.onUnauthorized(request) })
            }
        }
      }
    }
  }

  override def onError(request: RequestHeader, ex: Throwable) = {
    log.error("Global::onError", ex)
    Future.successful(handleError(request,ex.getCause))
  }

  override def onBadRequest(request: RequestHeader, error: String) = {
    log.info("Global::onBadRequest: " + error)
    Future.successful(BadRequest(Json.toJson(BadRequestException(
      resource = request.path,
      message = s"bad request: ${error}",
      developerMessage = s"Bad request: ${error}"
    ))))
  }

  override def onHandlerNotFound(request: RequestHeader) = {
    log.info(s"Global::onHandlerNotFound: ${request.path}")
    Future.successful(NotFound(Json.toJson(ResourceNotFoundException(
      resource = request.path,
      message = s"resource/endpoint not found",
      developerMessage = s"Resource/endpoint not found after authentication."
    ))))
  }

  override def onStart(app: Application): Unit = {
    scalikejdbc.GlobalSettings.loggingSQLErrors = false

    val db = EnvConfig.dbConnection getOrElse {
      throw new RuntimeException("FATAL: Database configuration not found.")
    }

    if (Init.isInit) {
      FlywayMigration.migrate(db, "", "")
    }

    val limitLength = EnvConfig.getEnvOpInt("OAUTH_RATE_LIMITING_PERIOD")
    val attemptPerLimit = EnvConfig.getEnvOpInt("OAUTH_RATE_LIMITING_AMOUNT")
    Akka.system().actorOf( RateLimitingActor.props(
      periodLengthMinutes = limitLength getOrElse 1,
      attemptsPerPeriod = attemptPerLimit getOrElse 10
    ), RateLimitingActor.ACTOR_NAME )
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
          resource = "",
          message = s"PSQL error ${sql.getSQLState}, ${sql.getErrorCode}",
          developerMessage = sql.getServerErrorMessage.getMessage
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

  val services = SecurityServices(
    accountStoreMappingService = new DefaultAccountStoreMappingServiceImpl
  )

}


