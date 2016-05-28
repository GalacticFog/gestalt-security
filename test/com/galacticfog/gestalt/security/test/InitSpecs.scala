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

  def clearInit() = InitSettingsRepository.find(0) foreach {
    _.copy(initialized = false).save()
  }

  sequential

  step({
    server.start()
    val connection = EnvConfig.dbConnection.get
    val baseDS = FlywayMigration.getDataSource(connection)
    val baseFlyway = new Flyway()
    baseFlyway.setDataSource(baseDS)
    baseFlyway.clean()
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

    "return OK on /health while uninitialized" in {
      await(client.url(s"http://localhost:${testServerPort}/health").get()).status must equalTo(OK)
    }

    "return info on /info while uninitialized" in {
      await(client.url(s"http://localhost:${testServerPort}/info").get()).status must equalTo(OK)
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

    "indicated initialized after initialization" in {
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
      init must haveSize(1)
      init(0).apiKey must_== prevApiKey.apiKey.toString
      init(0).apiSecret must beSome(prevApiKey.apiSecret)
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

  step({
    server.stop()
  })
}
