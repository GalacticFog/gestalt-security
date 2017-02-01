package com.galacticfog.gestalt.security

import com.galacticfog.gestalt.security.actors.RateLimitingActor
import controllers.{GestaltHeaderAuthentication, InitController, RESTAPIController}
import org.joda.time.Duration
import org.postgresql.util.PSQLException
import play.api.{Application, GlobalSettings, Play, Logger => log}
import play.libs.Akka

import scala.concurrent.Future
import com.galacticfog.gestalt.security.api.errors._
import com.galacticfog.gestalt.security.data.SecurityServices
import com.galacticfog.gestalt.security.data.config.ScalikePostgresDBConnection
import com.galacticfog.gestalt.security.data.domain.{DefaultAccountStoreMappingServiceImpl, OrgFactory}
import controllers.ControllerHelpers
import play.api._
import play.api.Play.current
import play.api.libs.json.{JsObject, Json}
import play.api.mvc.{Action, Handler, RequestHeader, Result}
import play.api.mvc.Results._
import scalikejdbc._

import scala.util.Try

object Global extends GlobalSettings with ControllerHelpers {

  import com.galacticfog.gestalt.security.api.json.JsonImports._

  /**
   * Indicates the query parameter name used to override the HTTP method
    *
    * @return a non-empty string indicating the query parameter. Popular choice is "_method"
   */

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

}


