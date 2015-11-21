import org.junit.runner._
import org.specs2.mutable._
import org.specs2.runner._
import play.api.test._

@RunWith(classOf[JUnitRunner])
class IntegrationSpec extends Specification {

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
    }.pendingUntilFixed

  }

}
