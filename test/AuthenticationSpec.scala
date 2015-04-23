import org.specs2.mutable._
import org.specs2.runner._
import org.junit.runner._

import play.api.test._
import play.api.test.Helpers._

/**
 * Add your spec here.
 * You can mock out a whole application including requests, plugins etc.
 * For more information, consult the wiki.
 */
@RunWith(classOf[JUnitRunner])
class AuthenticationSpec extends Specification {

  "REST Interface" should {

    "give current org" in new WithApplication() {
      ko("TODO")
    }

    "successfully authentication an existing user" in new WithApplication {
      ko("TODO")
    }

  }

  "SecurityController" should {

    "successfully authenticate an existing user" in {
      ko("TODO")
    }

  }

}
