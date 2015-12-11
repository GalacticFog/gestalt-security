import com.galacticfog.gestalt.security.Global
import com.galacticfog.gestalt.security.api._
import org.specs2.execute.{Results, Result}
import org.specs2.matcher.{MatchResult, Expectable, Matcher, ValueCheck, ValueChecks}
import play.api.libs.ws.WS
import play.api.test._

class SDKIntegrationSpec extends PlaySpecification {

  def hasName(expName: String) = new HasName(expName)
  def haveName(expName: String) = new HasName(expName)

  class HasName(expName: String) extends Matcher[GestaltResource] {
    override def apply[S <: GestaltResource](t: Expectable[S]): MatchResult[S] = {
      val actual = t.value.name
      result(
        expName == actual,
        s"resource name ${actual} was equal to ${expName}",
        s"resource name ${actual} was not equal to ${expName}",
        t
      )
    }
  }

  // default credentials on flyway are
  val ru = Global.DEFAULT_ROOT_USERNAME
  val rp = Global.DEFAULT_ROOT_PASSWORD

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
      val sync = await(GestaltOrg.syncOrgTree(None, ru, rp))
      sync.orgs     must contain(exactly(hasName("root")))
      sync.accounts must contain(exactly(hasName("root")))
    }

    "get all orgs" in {
      val orgs = await(GestaltOrg.getOrgs(ru, rp))
      orgs must contain(exactly(hasName("root")))
    }

    "get root org by fqon and id" in {
      val root1 = await(GestaltOrg.getByFQON("root", ru, rp))
      root1 must beSome
      val rootOrg = root1.get
      rootOrg must haveName("root")
      rootOrg.parent must beNone
      rootOrg.href must_== s"/orgs/${rootOrg.id}"
      rootOrg.children must beEmpty
      val root2 = await(GestaltOrg.getById(rootOrg.id, ru, rp))
      root2 must beSome(rootOrg)
      val curOrg = await(GestaltOrg.getCurrentOrg(ru,rp))
      curOrg must_== rootOrg
    }

    "perform framework authorization" in {
      // against implicit root org
      val auth1 = await(GestaltOrg.authorizeFrameworkUser(ru, rp))
      auth1 must beSome
      val ar = auth1.get
      // against explicit root org
      val auth2 = await(GestaltOrg.authorizeFrameworkUser("root", ru, rp))
      auth2 must beSome(ar)
      // against explicit org id
      val auth3 = await(GestaltOrg.authorizeFrameworkUser(ar.orgId, ru, rp))
      auth3 must beSome(ar)
    }

  }

}
