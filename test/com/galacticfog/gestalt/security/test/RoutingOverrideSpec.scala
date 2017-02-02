package com.galacticfog.gestalt.security.test

import com.galacticfog.gestalt.security.{Init, SecurityConfig}
import com.google.inject.AbstractModule
import org.joda.time.Duration
import org.junit.runner._
import org.specs2.mock.Mockito
import org.specs2.mutable._
import org.specs2.runner._
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.ws.WSClient
import play.api.mvc.Results._
import play.api.test.Helpers._
import play.api.test._
import play.api.mvc._
import play.api.routing._
import play.api.routing.sird._

/**
 * Add your spec here.
 * You can mock out a whole application including requests, plugins etc.
 * For more information, consult the wiki.
 */
@RunWith(classOf[JUnitRunner])
class RoutingOverrideSpec extends Specification with FutureAwaits with DefaultAwaitTimeout with Mockito {

  "Application Router" should {

    val mockInit = mock[Init]
    mockInit.isInit returns true
    val testConfig = SecurityConfig(
      tokenLifetime = Duration.ZERO,
      methodOverrideParameter = "_test_override",
      database = mock[SecurityConfig.DatabaseConfig],
      rateLimiting = mock[SecurityConfig.AuthAttemptConfig]
    )

    // fake route will return the method in the body, allowing us to verify the method that was used
    def appWithRoutes = new GuiceApplicationBuilder()
      .bindings(new AbstractModule {
        override def configure(): Unit = {
          bind(classOf[Init]).toInstance(mockInit)
          bind(classOf[SecurityConfig]).toInstance(testConfig)
        }
      })
      .disable(
        classOf[modules.DBModule],
        classOf[modules.RateLimitingActorModule],
        classOf[modules.DefaultModule]
      )
      .router(Router.from {
        case rh: RequestHeader if rh.path == "/health" => Action{Ok(rh.method)}
      })
      .build

    val client = appWithRoutes.injector.instanceOf[WSClient]

    "allow overriding HTTP GET via _test_override=POST" in new WithServer(app = appWithRoutes) {
      val resp = await(client.url(s"http://localhost:$testServerPort/health?_test_override=POST").get())
      resp.body must_== "POST"
    }

    "allow overriding HTTP GET via _test_override=post" in new WithServer(app = appWithRoutes) {
      val resp = await(client.url(s"http://localhost:$testServerPort/health?_test_override=post").get())
      resp.body must_== "POST"
    }

    "allow overriding HTTP POST via _test_override=PUT" in new WithServer(app = appWithRoutes) {
      val resp = await(client.url(s"http://localhost:$testServerPort/health?_test_override=PUT").post(""))
      resp.body must_== "PUT"
    }

    "allow overriding HTTP POST via _test_override=put" in new WithServer(app = appWithRoutes) {
      val resp = await(client.url(s"http://localhost:$testServerPort/health?_test_override=put").post(""))
      resp.body must_== "PUT"
    }

    "allow overriding HTTP POST via _test_override=DELETE" in new WithServer(app = appWithRoutes) {
      val resp = await(client.url(s"http://localhost:$testServerPort/health?_test_override=DELETE").post(""))
      resp.body must_== "DELETE"
    }

    "allow overriding HTTP POST via _test_override=delete" in new WithServer(app = appWithRoutes) {
      val resp = await(client.url(s"http://localhost:$testServerPort/health?_test_override=delete").post(""))
      resp.body must_== "DELETE"
    }

    "allow overriding HTTP GET via _test_override=PUT" in new WithServer(app = appWithRoutes) {
      val resp = await(client.url(s"http://localhost:$testServerPort/health?_test_override=PUT").get())
      resp.body must_== "PUT"
    }

    "allow overriding HTTP GET via _test_override=put" in new WithServer(app = appWithRoutes) {
      val resp = await(client.url(s"http://localhost:$testServerPort/health?_test_override=put").get())
      resp.body must_== "PUT"
    }

    "allow overriding HTTP GET via _test_override=DELETE" in new WithServer(app = appWithRoutes) {
      val resp = await(client.url(s"http://localhost:$testServerPort/health?_test_override=DELETE").get())
      resp.body must_== "DELETE"
    }

    "allow overriding HTTP GET via _test_override=delete" in new WithServer(app = appWithRoutes) {
      val resp = await(client.url(s"http://localhost:$testServerPort/health?_test_override=delete").get())
      resp.body must_== "DELETE"
    }
  }

}
