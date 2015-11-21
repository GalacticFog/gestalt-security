package app

import com.galacticfog.gestalt.security.api.errors._
import com.galacticfog.gestalt.security.data.SecurityServices
import com.galacticfog.gestalt.security.data.config.ScalikePostgresDBConnection
import com.galacticfog.gestalt.security.data.domain.{DefaultAccountStoreMappingServiceImpl, OrgFactory}
import play.api._
import org.flywaydb.core.Flyway
import org.apache.commons.dbcp2.BasicDataSource
import play.api.Play.current
import play.api.libs.json.Json
import play.api.mvc.{Result, RequestHeader}
import scala.collection.JavaConverters._
import play.api.{Logger => log}
import scala.concurrent.Future
import scala.util.Try
import play.api.mvc.Results._

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
    Future.successful(handleError(ex))
  }

  override def onBadRequest(request: RequestHeader, error: String) = {
    Logger.info("Global::onBadRequest: " + error)
    Future.successful(handleError(BadRequestException(
      resource = request.path,
      message = s"bad request: ${error}",
      developerMessage = s"Bad request: ${error}"
    )))
  }

  override def onHandlerNotFound(request: RequestHeader) = {
    Logger.info(s"Global::onHandlerNotFound: ${request.path}")
    Future.successful(handleError(ResourceNotFoundException(
      resource = request.path,
      message = s"resource/endpoint not found",
      developerMessage = s"Resource/endpoint not found. Most likely a result of referencing a non-existent org by org name."
    )))
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
      val doShutdown: Boolean = current.configuration.getBoolean("shutdownAfterMigrate") getOrElse false
      val rootUsername = current.configuration.getString("root.username") getOrElse "root"
      val rootPassword = current.configuration.getString("root.password") getOrElse "letmein"
      log.info("Migrating databases")
      FlywayMigration.migrate(connection, doClean, rootUsername = rootUsername, rootPassword = rootPassword)
      if (doShutdown) {
        log.info("Shutting because database.shutdownAfterMigrate == true")
        Play.stop()
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


  private def handleError(e: Throwable): Result = {
    e.getCause match {
      case notFound: ResourceNotFoundException => NotFound(Json.toJson(notFound))
      case badRequest: BadRequestException => BadRequest(Json.toJson(badRequest))
      case noauthc: UnauthorizedAPIException => Unauthorized(Json.toJson(noauthc))
      case noauthz: ForbiddenAPIException => Forbidden(Json.toJson(noauthz))
      case conflict: CreateConflictException => Conflict(Json.toJson(conflict))
      case unknown: UnknownAPIException => BadRequest(Json.toJson(unknown)) // not sure why this would happen, but if we have that level of info, might as well use it
      case nope: Throwable => InternalServerError(Json.toJson(UnknownAPIException(
        code = 500, resource = "", message = "internal server error", developerMessage = "Internal server error. Please check the log for more details."
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
      val ds = new BasicDataSource();
      ds.setDriverClassName(info.driver);
      ds.setUsername(info.username);
      ds.setPassword(info.password);
      ds.setUrl(info.url)
      log.info("url: " + ds.getUrl)
      ds
    }

    val baseFlyway = new Flyway()
    val baseDS = getDataSource(info)
    val mlevel1 = Try {
      baseFlyway.setDataSource(baseDS)
      baseFlyway.setLocations("classpath:db/migration/base")
      baseFlyway.setPlaceholders(Map(
        "root_username" -> rootUsername,
        "root_password" -> rootPassword
      ).asJava)
      if (clean) baseFlyway.clean()
      baseFlyway.migrate()
    }
    if ( ! baseDS.isClosed ) try {
      baseDS.close
    } catch {
      case e: Throwable => log.error("error closing base datasource",e)
    }
    log.info("Base DB migrated to level " + mlevel1)
  }

}
