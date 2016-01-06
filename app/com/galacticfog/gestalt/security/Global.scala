package com.galacticfog.gestalt.security

import org.postgresql.util.PSQLException
import play.api.{Application, GlobalSettings, Logger => log, Play}
import scala.collection.JavaConverters._
import scala.concurrent.Future
import com.galacticfog.gestalt.security.api.errors._
import com.galacticfog.gestalt.security.data.SecurityServices
import com.galacticfog.gestalt.security.data.config.ScalikePostgresDBConnection
import com.galacticfog.gestalt.security.data.domain.DefaultAccountStoreMappingServiceImpl
import play.api._
import org.flywaydb.core.Flyway
import org.apache.commons.dbcp2.BasicDataSource
import play.api.Play.current
import play.api.libs.json.Json
import play.api.mvc.{Result, RequestHeader}
import scala.util.{Failure, Success, Try}
import play.api.mvc.Results._

object Global extends GlobalSettings with GlobalWithMethodOverriding {

  val DEFAULT_ROOT_USERNAME = "root"
  val DEFAULT_ROOT_PASSWORD = "letmein"

  import com.galacticfog.gestalt.security.api.json.JsonImports._

  /**
   * Indicates the query parameter name used to override the HTTP method
   * @return a non-empty string indicating the query parameter. Popular choice is "_method"
   */
  override def overrideParameter: String = "_method"

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
      val rootUsername = current.configuration.getString("root.username") getOrElse DEFAULT_ROOT_USERNAME
      val rootPassword = current.configuration.getString("root.password") getOrElse DEFAULT_ROOT_PASSWORD
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

object FlywayMigration {

  def migrate(info: ScalikePostgresDBConnection, clean: Boolean,
              rootUsername: String, rootPassword: String) =
  {
    def getDataSource(info: ScalikePostgresDBConnection) = {
      val ds = new BasicDataSource()
      ds.setDriverClassName(info.driver)
      ds.setUsername(info.username)
      ds.setPassword(info.password)
      ds.setUrl(info.url)
      log.info("url: " + ds.getUrl)
      ds
    }

    val baseFlyway = new Flyway()
    val baseDS = getDataSource(info)
    val migLevel = Try {
      baseFlyway.setDataSource(baseDS)
      baseFlyway.setLocations("classpath:db/migration/base")
      baseFlyway.setPlaceholders(Map(
        "root_username" -> rootUsername,
        "root_password" -> rootPassword
      ).asJava)
      if (clean) {
        log.info("cleaning database")
        baseFlyway.clean()
      }
      baseFlyway.migrate()
    }
    if ( ! baseDS.isClosed ) try {
      baseDS.close()
    } catch {
      case e: Throwable => log.error("error closing base datasource",e)
    }
    migLevel match {
      case Success(l) => log.info("Base DB migrated to level " + l)
      case Failure(ex) => throw ex
    }

  }

}
