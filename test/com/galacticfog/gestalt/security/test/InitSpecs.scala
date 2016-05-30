package com.galacticfog.gestalt.security.test

import java.util.UUID

import com.galacticfog.gestalt.security.api._
import com.galacticfog.gestalt.security.api.errors.BadRequestException
import com.galacticfog.gestalt.security.data.domain._
import com.galacticfog.gestalt.security.data.model.{InitSettingsRepository, UserAccountRepository}
import com.galacticfog.gestalt.security.{EnvConfig, FlywayMigration}
import controllers.InitRequest
import org.flywaydb.core.Flyway
import play.api.libs.json.Json
import play.api.libs.ws.WS
import play.api.test._
import com.galacticfog.gestalt.security.api.json.JsonImports._

class InitSpecs extends PlaySpecification {

  lazy val fakeApp = FakeApplication()
  lazy val server = TestServer(port = testServerPort, application = fakeApp)

  lazy val initUsername = "init-user"
  lazy val initPassword = "init password123"

  def clearInit() = InitSettings.getInitSettings() foreach {
    _.copy(initialized = false).save()
  }

  def clearDB() = {
    val connection = EnvConfig.dbConnection.get
    val baseDS = FlywayMigration.getDataSource(connection)
    val baseFlyway = new Flyway()
    baseFlyway.setDataSource(baseDS)
    baseFlyway.clean()
  }

  sequential

  step({
    server.start()
    clearDB()
  })

  val client = WS.client(fakeApp)

  // testing against /init only, so we don't need creds
  lazy implicit val sdk: GestaltSecurityClient = GestaltSecurityClient(
    wsclient = client,
    protocol = HTTP,
    hostname = "localhost",
    port = testServerPort,
    creds = GestaltBasicCredentials("","")
  )

  "Service" should {

    "begin uninitialized" in {
      (await(sdk.getJson("init")) \ "initialized").asOpt[Boolean] must beSome(false)
    }

    "return 500 on /health while uninitialized" in {
      await(client.url(s"http://localhost:${testServerPort}/health").get()).status must equalTo(INTERNAL_SERVER_ERROR)
    }

    "return 200 with info on /info while uninitialized" in {
      await(client.url(s"http://localhost:${testServerPort}/info").get()).status must equalTo(OK)
    }

    "throw 400 on other requests" in {
      val resp = await(client.url(s"http://localhost:${testServerPort}/root").get())
      resp.status must_== BAD_REQUEST
      resp.body must contain("not initialized")
    }

    "pre-4 initialization requires username" in {
      await(sdk.post[Seq[GestaltAPIKey]](
        uri = "init",
        payload = Json.toJson(InitRequest(
          username = None
        ))
      )) must throwA[BadRequestException](".*requires username.*")
    }

    "allow initialization, create account and return valid API keys" in {
      val initUsername = "init-user"
      val initPassword = "init password123"
      val init = await(sdk.post[Seq[GestaltAPIKey]](
        uri = "init",
        payload = Json.toJson(InitRequest(
          username = Some(initUsername),
          password = Some(initPassword)
        ))
      ))
      init must haveSize(1)
      init(0).apiSecret must beSome
      val initAccount = AccountFactory.find(init(0).accountId)
      initAccount must beSome((u: UserAccountRepository) => u.username == initUsername)
      AccountFactory.checkPassword(initAccount.get, initPassword) must beTrue
      InitSettingsRepository.find(0) must beSome((init: InitSettingsRepository) =>
        init.rootAccount exists (_ == initAccount.get.id)
      )
      val newSdk = sdk.withCreds(
        GestaltBasicCredentials(init(0).apiKey, init(0).apiSecret.get)
      )
      await(GestaltAccount.getSelf()(newSdk)).username must_== initUsername
      val rootOrg = await(GestaltOrg.getCurrentOrg()(newSdk))
      val auth = await(GestaltOrg.authorizeFrameworkUser(newSdk.creds))
      auth must beSome
    }

  }

  "Initialized service" should {

    "indicate initialized after initialization" in {
      (await(sdk.getJson("init")) \ "initialized").asOpt[Boolean] must beSome(true)
    }

    "not allow initialization" in {
      await(sdk.post[Seq[GestaltAPIKey]](
        uri = "init",
        payload = Json.toJson(InitRequest())
      )) must throwA[BadRequestException](".*already initialized.*")
    }

  }

  "Re-initialization" should {

    "support repasswording and return existing api keys" in {
      clearInit()
      val newPassword = "#*#don't Guess My New Password#*#"
      val prevInitAccount = InitSettingsRepository.find(0).
        flatMap {_.rootAccount}.
        flatMap (UserAccountRepository.find) get
      val prevApiKeyList = APICredentialFactory.findByAccountId(prevInitAccount.id.asInstanceOf[UUID])
      prevApiKeyList must haveSize(1)
      val prevApiKey = prevApiKeyList(0)
      val init = await(sdk.post[Seq[GestaltAPIKey]](
        uri = "init",
        payload = Json.toJson(InitRequest(
          username = Some(initUsername),
          password = Some(newPassword)
        ))
      ))
      init must containTheSameElementsAs(prevApiKeyList)
      AccountFactory.checkPassword(prevInitAccount, newPassword) must beTrue
      (await(sdk.getJson("init")) \ "initialized").asOpt[Boolean] must beSome(true)
    }

    "issue new api key if none exist" in {
      clearInit()
      val prevInitAccount = InitSettingsRepository.find(0).
        flatMap {_.rootAccount}.
        flatMap (UserAccountRepository.find).
        get
      APICredentialFactory.findByAccountId(prevInitAccount.id.asInstanceOf[UUID]) foreach {
        _.destroy()
      }
      val init = await(sdk.post[Seq[GestaltAPIKey]](
        uri = "init",
        payload = Json.toJson(InitRequest())
      ))
      init must haveSize(1)
      val newApiKey = init(0)
      newApiKey.apiSecret must beSome
      newApiKey.accountId must_== prevInitAccount.id
      (await(sdk.getJson("init")) \ "initialized").asOpt[Boolean] must beSome(true)
    }

  }

  "Init from V4+" should {

    "succeed and return keys" in {
      clearDB()
      FlywayMigration.migrate(EnvConfig.dbConnection.get, "legacy-root", "letmein", Some("9"))
      val init = await(sdk.post[Seq[GestaltAPIKey]](
        uri = "init",
        payload = Json.toJson(InitRequest())
      ))
      init must haveSize(1)
      val newApiKey = init(0)
      newApiKey.apiSecret must beSome
      AccountFactory.find(newApiKey.accountId) must beSome(
        (uar: UserAccountRepository) => uar.username == "legacy-root"
      )
      (await(sdk.getJson("init")) \ "initialized").asOpt[Boolean] must beSome(true)
    }

  }

  step({
    server.stop()
  })
}
