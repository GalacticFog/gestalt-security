package com.galacticfog.gestalt.security.test

import com.galacticfog.gestalt.security.data.domain.AccountStoreMappingService
import com.galacticfog.gestalt.security.{Init, SecurityConfig}
import com.google.inject.AbstractModule
import org.joda.time.Duration
import org.junit.runner._
import org.specs2.mock.Mockito
import org.specs2.mutable._
import org.specs2.runner._
import play.api.Application
import play.api.http.HeaderNames
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.ws.WSClient
import play.api.mvc.Results._
import play.api.mvc._
import play.api.routing._
import play.api.test.Helpers._
import play.api.test._

import scala.util.Success

/**
 * Add your spec here.
 * You can mock out a whole application including requests, plugins etc.
 * For more information, consult the wiki.
 */
@RunWith(classOf[JUnitRunner])
class CORSSpec extends Specification with FutureAwaits with DefaultAwaitTimeout with Mockito {

  "Application" should {

    val mockInit = mock[Init]
    mockInit.isInit returns Success(true)
    val testConfig = SecurityConfig(
      tokenLifetime = Duration.standardMinutes(0),
      methodOverrideParameter = "_test_override",
      database = mock[SecurityConfig.DatabaseConfig],
      rateLimiting = SecurityConfig.AuthAttemptConfig(
        periodInMinutes = 1,
        attemptsPerPeriod = 10
      )
    )

    def minimalApp = new GuiceApplicationBuilder()
      .bindings(new AbstractModule {
        override def configure(): Unit = {
          bind(classOf[Init]).toInstance(mockInit)
          bind(classOf[SecurityConfig]).toInstance(testConfig)
          bind(classOf[AccountStoreMappingService]).toInstance(mock[AccountStoreMappingService])
        }
      })
      .disable(
        classOf[modules.DBModule],
        classOf[modules.DefaultModule]
      )
      .build

    def client(implicit app: Application) = app.injector.instanceOf[WSClient]

    "allow overriding HTTP GET via _test_override=POST" in new WithServer(app = minimalApp) {
      val resp = await(client.url(s"http://localhost:$testServerPort/accounts/self")
        .withHeaders(HeaderNames.ORIGIN -> "http://somewhere.else.com:9000")
        .options())
      resp.allHeaders must havePairs(
        HeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN -> Seq("http://somewhere.else.com:9000")
      )
    }

  }

}
