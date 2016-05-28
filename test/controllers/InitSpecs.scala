package controllers

import java.util.UUID
import com.galacticfog.gestalt.security.EnvConfig
import com.galacticfog.gestalt.security.api.AccessTokenResponse.BEARER
import com.galacticfog.gestalt.security.api.GestaltToken.ACCESS_TOKEN
import com.galacticfog.gestalt.security.api._
import com.galacticfog.gestalt.security.api.errors.{BadRequestException, ConflictException, OAuthError, UnauthorizedAPIException}
import com.galacticfog.gestalt.security.api.json.JsonImports._
import com.galacticfog.gestalt.security.data.APIConversions._
import com.galacticfog.gestalt.security.data.domain._
import com.galacticfog.gestalt.security.data.model.{InitSettingsRepository, UserAccountRepository, APICredentialRepository, TokenRepository}
import org.joda.time.DateTime
import org.specs2.matcher.{Expectable, MatchResult, Matcher}
import play.api.libs.json.Json
import play.api.libs.ws.WS
import play.api.test._
import com.galacticfog.gestalt.security.api.json.JsonImports._
import InitRequest.initRequestFormat

class InitSpecs extends PlaySpecification {

  lazy val fakeApp = FakeApplication()
  lazy val server = TestServer(port = testServerPort, application = fakeApp)

  lazy val initUsername = "init-user"
  lazy val initPassword = "init password123"

  sequential

  step({
      server.start()
  })

  val client = WS.client(fakeApp)

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

  step({
    server.stop()
  })
}
