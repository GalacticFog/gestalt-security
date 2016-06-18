package com.galacticfog.gestalt.security.test

import java.util.UUID

import com.galacticfog.gestalt.security.api.GestaltToken.ACCESS_TOKEN
import com.galacticfog.gestalt.security.api._
import com.galacticfog.gestalt.security.data.APIConversions._
import com.galacticfog.gestalt.security.data.domain._
import com.galacticfog.gestalt.security.{EnvConfig, FlywayMigration, InitRequest}
import org.flywaydb.core.Flyway
import org.specs2.matcher.{Expectable, MatchResult, Matcher}
import play.api.libs.json.Json
import play.api.libs.ws.WS
import play.api.test._
import com.galacticfog.gestalt.security.api.json.JsonImports._

trait SpecWithSDK extends PlaySpecification {

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

  def clearDB() = {
    val connection = EnvConfig.dbConnection.get
    val baseDS = FlywayMigration.getDataSource(connection)
    val baseFlyway = new Flyway()
    baseFlyway.setDataSource(baseDS)
    baseFlyway.clean()
  }

  // default credentials on flyway are
  val rootUsername = "root-user"
  val rootPassword = "root password123"
  val rootAccountCreds = GestaltBasicCredentials(rootUsername,rootPassword)

  lazy val fakeApp = FakeApplication()
  lazy val server = TestServer(port = testServerPort, application = fakeApp)

  sequential

  step({
    server.start()
    clearDB()
  })

  val client = WS.client(fakeApp)

  lazy val rootApiKey = await(client.url(s"http://localhost:${testServerPort}/init").post(
    Json.toJson(InitRequest(
      username = Some(rootUsername),
      password = Some(rootPassword)
    )))).json.as[Seq[GestaltAPIKey]].head
  lazy val rootApiCreds = GestaltBasicCredentials(rootApiKey.apiKey, rootApiKey.apiSecret.get)

  lazy implicit val keySdk: GestaltSecurityClient = GestaltSecurityClient(
    wsclient = client,
    protocol = HTTP,
    hostname = "localhost",
    port = testServerPort,
    creds = rootApiCreds
  )

  lazy val rootAccount: GestaltAccount = await(GestaltAccount.getSelf())
  lazy val rootOrg: GestaltOrg = await(GestaltOrg.getCurrentOrg())
  lazy val daoRootDir: Directory = DirectoryFactory.listByOrgId(rootOrg.id).head
  lazy val rootAdminsGroup: GestaltGroup = daoRootDir.lookupGroups("admins").head
  lazy val rootOrgApp: GestaltApp = await(rootOrg.getServiceApp())

  // create a token-based sdk for testing
  lazy val rootAccessToken = TokenFactory.createToken(Some(rootOrg.id), rootAccount.id, 28800, ACCESS_TOKEN, None).get
  lazy val rootBearerCreds = GestaltBearerCredentials(OpaqueToken(rootAccessToken.id.asInstanceOf[UUID], GestaltToken.ACCESS_TOKEN).toString)
  lazy val tokenSdk: GestaltSecurityClient = keySdk.withCreds(rootBearerCreds)

  lazy val anonSdk: GestaltSecurityClient = keySdk.withCreds(GestaltBearerCredentials(UUID.randomUUID().toString))

  lazy val rootDir: GestaltDirectory = await(rootOrg.listDirectories()).head

}
