import com.galacticfog.gestalt.security.api.{GestaltAccount, GestaltOrg, HTTP, GestaltSecurityClient}
import org.specs2.execute.Result
import org.specs2.matcher.ValueCheck
import play.api.libs.ws.WS
import play.api.test._

class SDKIntegrationSpec extends PlaySpecification {

  lazy val fakeApp = FakeApplication(additionalConfiguration = Map(
    "database.host" -> scala.sys.env.getOrElse("TESTDB_HOST","localhost"),
    "database.dbname" -> scala.sys.env.getOrElse("TESTDB_DBNAME","gestalt-security-test"),
    "database.port" -> scala.sys.env.getOrElse("TESTDB_PORT", "5432").toInt,
    "database.username" -> scala.sys.env.getOrElse("TESTDB_USERNAME","testdbuser"),
    "database.password" -> scala.sys.env.getOrElse("TESTDB_PASSWORD","testdbpass"),
    "database.migrate" -> true,
    "database.clean" -> true,
    "database.shutdownAfterMigrate" -> false,
    "database.timeoutMs" -> scala.sys.env.getOrElse("TESTDB_TIMEOUTMS","5000").toInt
  ))
  lazy val server = TestServer(port = testServerPort, application = fakeApp)

  stopOnFail
  sequential

  textFragment("Begin application and migrate database")
  step({
      server.start()
  })

  "SDK client" should {
    val client = WS.client(fakeApp)
    implicit val sdk: GestaltSecurityClient = GestaltSecurityClient(client, protocol = HTTP, hostname = "localhost", port = testServerPort, "", "")

    "return OK on /health" in {
      await(client.url(s"http://localhost:${testServerPort}/health").get()).status must equalTo(OK)
    }

    "verify fresh: syncs root with admin" in {
      val sync = await(GestaltOrg.syncOrgTree(None, "root", "letmein"))
      sync.orgs must contain(exactly(
        be_==("root") ^^ { (o: GestaltOrg) => o.name }
      ))
      sync.accounts must contain(exactly(
        be_==("root") ^^ { (a: GestaltAccount) => a.username }
      ))
    }

  }

}
