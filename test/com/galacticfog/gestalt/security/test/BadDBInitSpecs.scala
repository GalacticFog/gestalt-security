package com.galacticfog.gestalt.security.test

import java.util.UUID

import com.galacticfog.gestalt.security.api._
import com.galacticfog.gestalt.security.api.errors.{BadRequestException, UnknownAPIException}
import com.galacticfog.gestalt.security.api.json.JsonImports._
import com.galacticfog.gestalt.security.data.APIConversions._
import com.galacticfog.gestalt.security.data.domain._
import com.galacticfog.gestalt.security.data.model.{InitSettingsRepository, UserAccountRepository}
import com.galacticfog.gestalt.security.{FlywayMigration, Init, InitRequest, SecurityConfig}
import modules.DatabaseConnection
import org.flywaydb.core.Flyway
import org.joda.time.Duration
import org.specs2.mock.Mockito
import play.api.Logger
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.ws.WSClient
import play.api.test._
import play.api.inject._
import play.api.libs.json.Json
import scala.concurrent.duration._

import scala.concurrent.{Await, Future}

class BadDBInitSpecs extends PlaySpecification with Mockito {

  lazy val fakeApp = GuiceApplicationBuilder()
    .overrides(bind(classOf[SecurityConfig]).toInstance(SecurityConfig(
      tokenLifetime = Duration.standardHours(8),
      methodOverrideParameter = "_whatever",
      database = SecurityConfig.DatabaseConfig("bad-host", "bad-username", "bad-password", "bad-db", 5432, 5000),
      rateLimiting = SecurityConfig.AuthAttemptConfig(1,100)
    )))
    .build
  lazy val server = TestServer(port = testServerPort, application = fakeApp)

  lazy val client = fakeApp.injector.instanceOf[WSClient]

  sequential

  step({
    server.start()
  })

  // testing against /init only, so we don't need creds
  lazy implicit val sdk: GestaltSecurityClient = GestaltSecurityClient(
    wsclient = client,
    protocol = HTTP,
    hostname = "localhost",
    port = testServerPort,
    creds = GestaltBasicCredentials("","")
  )

  "Service with bad database credentials/connectivity" should {

    "return 500 on /init" in {
      await(sdk.getJson("init")) must throwAn[UnknownAPIException].like({
        case UnknownAPIException(500,_,message,_) => message must contain("database exception")
      })
    }

  }

  step({
    server.stop()
  })
}
