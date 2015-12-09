package com.galacticfog.gestalt.security

import app.GlobalWithMethodOverriding
import com.galacticfog.gestalt.security.api.errors._
import com.galacticfog.gestalt.security.data.SecurityServices
import com.galacticfog.gestalt.security.data.config.ScalikePostgresDBConnection
import com.galacticfog.gestalt.security.data.domain.DefaultAccountStoreMappingServiceImpl
import play.api.Play._
import play.api.libs.json.Json
import play.api.mvc.Results._
import play.api.mvc.{RequestHeader, Result}
import play.api.{Application, GlobalSettings, Logger, Play}

import scala.concurrent.Future

/**
  * Created by cgbaker on 12/9/15.
  */
// Note: this is in the default package.
object Global extends GlobalSettings with GlobalWithMethodOverriding {

  import com.galacticfog.gestalt.security.api.json.JsonImports._

  /**
   * Indicates the query parameter name used to override the HTTP method
   * @return a non-empty string indicating the query parameter. Popular choice is "_method"
   */
  override def overrideParameter: String = "_method"

  override def onError(request: RequestHeader, ex: Throwable) = {
    Logger.info("Global::onError: " + ex.getMessage)
    Future.successful(handleError(request,ex))
  }

  override def onBadRequest(request: RequestHeader, error: String) = {
    Logger.info("Global::onBadRequest: " + error)
    Future.successful(BadRequest(Json.toJson(BadRequestException(
      resource = request.path,
      message = s"bad request: ${error}",
      developerMessage = s"Bad request: ${error}"
    ))))
  }

  override def onHandlerNotFound(request: RequestHeader) = {
    Logger.info(s"Global::onHandlerNotFound: ${request.path}")
    Future.successful(NotFound(Json.toJson(ResourceNotFoundException(
      resource = request.path,
      message = s"resource/endpoint not found",
      developerMessage = s"Resource/endpoint not found after authentication."
    ))))
  }

  override def onStart(app: Application): Unit = {
    val connection = current.configuration.getObject("database") match {
      case None =>
        throw new RuntimeException("FATAL: Database configuration not found.")
      case Some(config) => {
        val configMap = config.unwrapped.asScala.toMap
        displayStartupSettings(configMap)
        ScalikePostgresDBConnection(
          host = configMap("host").toString,
          database = configMap("dbname").toString,
          port = configMap("port").toString.toInt,
          username = configMap("username").toString,
          password = configMap("password").toString,
          timeoutMs = configMap("timeoutMs").toString.toLong)
      }
    }

    val doMigrate: Boolean = current.configuration.getBoolean("database.migrate") getOrElse false

    if (doMigrate) {
      val doClean: Boolean = current.configuration.getBoolean("database.clean") getOrElse false
      val doShutdown: Boolean = current.configuration.getBoolean("database.shutdownAfterMigrate") getOrElse false
      val rootUsername = current.configuration.getString("root.username") getOrElse "root"
      val rootPassword = current.configuration.getString("root.password") getOrElse "letmein"
      log.info("Migrating databases")
      FlywayMigration.migrate(connection, doClean, rootUsername = rootUsername, rootPassword = rootPassword)
      if (doShutdown) {
        log.info("Shutting because database.shutdownAfterMigrate == true")
        Play.stop()
        scala.sys.exit()
      }
    }
  }

  private def displayStartupSettings(config: Map[String, Object]) {
    log.debug("DATABASE SETTINGS:")
    for ((k,v) <- config) {
      if (k != "password")
        log.debug("%s = '%s'".format(k, v.toString))
    }
  }


  private def handleError(request: RequestHeader, e: Throwable): Result = {
    val resource = e.getCause match {
      case sre: SecurityRESTException if !sre.resource.isEmpty =>
        sre.resource
      case _ => request.path
    }
    e.getCause match {
      case notFound: ResourceNotFoundException => NotFound(Json.toJson(notFound.copy(resource = resource)))
      case badRequest: BadRequestException => BadRequest(Json.toJson(badRequest.copy(resource = resource)))
      case noauthc: UnauthorizedAPIException => Unauthorized(Json.toJson(noauthc.copy(resource = resource)))
      case noauthz: ForbiddenAPIException => Forbidden(Json.toJson(noauthz))
      case conflict: CreateConflictException => Conflict(Json.toJson(conflict.copy(resource = resource)))
      case unknown: UnknownAPIException => BadRequest(Json.toJson(unknown.copy(resource = resource))) // not sure why this would happen, but if we have that level of info, might as well use it
      case nope: Throwable =>
        nope.printStackTrace()
        InternalServerError(Json.toJson(UnknownAPIException(
        code = 500, resource = request.path, message = "internal server error", developerMessage = "Internal server error. Please check the log for more details."
      )))
    }
  }

  val services = SecurityServices(
    accountStoreMappingService = new DefaultAccountStoreMappingServiceImpl
  )

}
