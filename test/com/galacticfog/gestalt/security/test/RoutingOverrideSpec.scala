package com.galacticfog.gestalt.security.test

import com.galacticfog.gestalt.security.GlobalWithMethodOverriding
import org.junit.runner._
import org.specs2.mutable._
import org.specs2.runner._
import play.api.libs.ws.WS
import play.api.mvc.Action
import play.api.mvc.Results._
import play.api.test.Helpers._
import play.api.test._

/**
 * Add your spec here.
 * You can mock out a whole application including requests, plugins etc.
 * For more information, consult the wiki.
 */
@RunWith(classOf[JUnitRunner])
class RoutingOverrideSpec extends Specification with FutureAwaits with DefaultAwaitTimeout {

  "Application Router" should {

    // fake route will return the method in the body, allowing us to verify the method that was used
    val appWithRoutes = FakeApplication(withRoutes = {
      case (method, "/") => Action { Ok(method) }
    }, withGlobal = Some(new GlobalWithMethodOverriding {
      override def overrideParameter: String = "_method"
    }))

    "allow overriding HTTP GET via _method=POST" in new WithServer(app = appWithRoutes, port = testServerPort) {
      val resp = await(WS.url(s"http://localhost:$testServerPort?_method=POST").get())
      resp.body must_== "POST"
    }

    "allow overriding HTTP GET via _method=post" in new WithServer(app = appWithRoutes, port = testServerPort) {
      val resp = await(WS.url(s"http://localhost:$testServerPort?_method=post").get())
      resp.body must_== "POST"
    }

    "allow overriding HTTP POST via _method=PUT" in new WithServer(app = appWithRoutes, port = testServerPort) {
      val resp = await(WS.url(s"http://localhost:$testServerPort?_method=PUT").post(""))
      resp.body must_== "PUT"
    }

    "allow overriding HTTP POST via _method=put" in new WithServer(app = appWithRoutes, port = testServerPort) {
      val resp = await(WS.url(s"http://localhost:$testServerPort?_method=put").post(""))
      resp.body must_== "PUT"
    }

    "allow overriding HTTP POST via _method=DELETE" in new WithServer(app = appWithRoutes, port = testServerPort) {
      val resp = await(WS.url(s"http://localhost:$testServerPort?_method=DELETE").post(""))
      resp.body must_== "DELETE"
    }

    "allow overriding HTTP POST via _method=delete" in new WithServer(app = appWithRoutes, port = testServerPort) {
      val resp = await(WS.url(s"http://localhost:$testServerPort?_method=delete").post(""))
      resp.body must_== "DELETE"
    }

    "allow overriding HTTP GET via _method=PUT" in new WithServer(app = appWithRoutes, port = testServerPort) {
      val resp = await(WS.url(s"http://localhost:$testServerPort?_method=PUT").get())
      resp.body must_== "PUT"
    }

    "allow overriding HTTP GET via _method=put" in new WithServer(app = appWithRoutes, port = testServerPort) {
      val resp = await(WS.url(s"http://localhost:$testServerPort?_method=put").get())
      resp.body must_== "PUT"
    }

    "allow overriding HTTP GET via _method=DELETE" in new WithServer(app = appWithRoutes, port = testServerPort) {
      val resp = await(WS.url(s"http://localhost:$testServerPort?_method=DELETE").get())
      resp.body must_== "DELETE"
    }

    "allow overriding HTTP GET via _method=delete" in new WithServer(app = appWithRoutes, port = testServerPort) {
      val resp = await(WS.url(s"http://localhost:$testServerPort?_method=delete").get())
      resp.body must_== "DELETE"
    }
  }

}
