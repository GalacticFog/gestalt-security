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
import play.api.libs.json.{JsObject, Json}
import play.api.mvc.{Result, RequestHeader}
import scala.util.{Failure, Success, Try}
import play.api.mvc.Results._
import scalikejdbc._

object EnvConfig {
  val DEFAULT_ROOT_USERNAME = "root"
  val DEFAULT_ROOT_PASSWORD = "letmein"
  val DEFAULT_DB_TIMEOUT: Long = 5000
  val DEFAULT_DB_PORT: Int = 5432

  def getEnvOpt(name: String): Option[String] = {
    System.getenv(name) match {
      case null => None
      case empty if empty.trim.isEmpty => None
      case okay => Some(okay)
    }
  }

  def getEnvOpInt(name: String): Option[Int] = {
    getEnvOpt(name) flatMap { s => Try{s.toInt}.toOption }
  }

  def getEnvOptBoolean(name: String): Option[Boolean] = {
    getEnvOpt(name) flatMap { s => Try{s.toBoolean}.toOption }
  }

  lazy val migrateDatabase = getEnvOptBoolean("MIGRATE_DATABASE") getOrElse true
  lazy val cleanOnMigrate = getEnvOptBoolean("CLEAN_ON_MIGRATE") getOrElse false
  lazy val shutdownAfterMigrate = getEnvOptBoolean("SHUTDOWN_AFTER_MIGRATE") getOrElse false

  lazy val rootUsername = getEnvOpt("ROOT_USERNAME") getOrElse DEFAULT_ROOT_USERNAME
  lazy val rootPassword = getEnvOpt("ROOT_PASSWORD") getOrElse DEFAULT_ROOT_PASSWORD

  private lazy val host = getEnvOpt("DATABASE_HOSTNAME")
  private lazy val username = getEnvOpt("DATABASE_USERNAME")
  private lazy val password = getEnvOpt("DATABASE_PASSWORD")
  private lazy val dbname = getEnvOpt("DATABASE_NAME")
  private lazy val port = getEnvOpt("DATABASE_PORT")
  private lazy val timeout = getEnvOpt("DATABASE_TIMEOUT_MS")

  lazy val dbConnection = {
    log.info(EnvConfig.toString)
    for {
      host <- host
      username <- username
      password <- password
      dbname <- dbname
      port <- port flatMap { s => Try{s.toInt}.toOption} orElse Some(DEFAULT_DB_PORT)
      timeout <- timeout flatMap { s => Try{s.toLong}.toOption} orElse Some(DEFAULT_DB_TIMEOUT)
    } yield ScalikePostgresDBConnection(
      host = host,
      port = port,
      database = dbname,
      username = username,
      password = password,
      timeoutMs = timeout
    )
  }

  lazy val databaseUrl = {
    "jdbc:postgresql://%s:%s/%s?user=%s&password=%s".format(
      host getOrElse "undefined",
      port getOrElse 9455,
      dbname getOrElse "undefined",
      username getOrElse "undefined",
      password map {_ => "*****"} getOrElse "undefined"
    )
  }

  override def toString = {
    s"""
       |EnvConfig(
       |  database = ${databaseUrl},
       |  migrateDatabase = ${migrateDatabase},
       |  cleanOnMigrate = ${cleanOnMigrate},
       |  shutdownAfterMigrate = ${shutdownAfterMigrate}
       |)
    """.stripMargin
  }
}

object Global extends GlobalSettings with GlobalWithMethodOverriding {

  import com.galacticfog.gestalt.security.api.json.JsonImports._

  /**
   * Indicates the query parameter name used to override the HTTP method
    *
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
    scalikejdbc.GlobalSettings.loggingSQLErrors = false

    val connection = EnvConfig.dbConnection getOrElse {
      throw new RuntimeException("FATAL: Database configuration not found.")
    }

    if (EnvConfig.migrateDatabase) {
      log.info("Migrating databases")
      FlywayMigration.migrate(
        info = connection,
        clean = EnvConfig.cleanOnMigrate,
        rootUsername = EnvConfig.rootUsername,
        rootPassword = EnvConfig.rootPassword
      )
      if (EnvConfig.shutdownAfterMigrate) {
        log.info("Shutting because database.shutdownAfterMigrate == true")
        Play.stop()
        scala.sys.exit()
      }
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
      case Success(l) => log.info(s"Base DB migrated ${l} levels")
      case Failure(ex) => throw ex
    }

  }

}
