package com.galacticfog.gestalt.security.test

import java.util.UUID

import com.galacticfog.gestalt.security.api.GestaltToken.ACCESS_TOKEN
import com.galacticfog.gestalt.security.api.{GestaltOrg, _}
import com.galacticfog.gestalt.security.api.errors.{ResourceNotFoundException, UnauthorizedAPIException, BadRequestException, ConflictException}
import com.galacticfog.gestalt.security.api.json.JsonImports._
import com.galacticfog.gestalt.security.data.APIConversions._
import com.galacticfog.gestalt.security.data.domain._
import com.galacticfog.gestalt.security.data.model.UserAccountRepository
import com.galacticfog.gestalt.security.utils.SecureIdGenerator
import org.specs2.matcher.{Matcher, JsonMatchers}
import play.api.libs.json.Json

class AccountSpecs extends SpecWithSDK with JsonMatchers {

  val rootPhone = "+1.505.867.5309"
  val rootEmail = "root@root"

  lazy val testAccount = await(rootDir.createAccount(GestaltAccountCreate(
    username = "test",
    firstName = "test",
    description = Some("some user"),
    lastName = "user",
    email = Some("test@root"),
    phoneNumber = Some("+1.555.555.5555"),
    credential = GestaltPasswordCredential("letmein")
  )))


  def beADisabledAccount: Matcher[UserAccountRepository] = { uar: UserAccountRepository =>
    (uar.disabled, uar.username+" is disabled", uar.username+" is not disabled")
  }


  "Accounts" should {

    "be listed in dirs by username" in {
      await(rootDir.getAccountByUsername(testAccount.username)) must beSome(testAccount)
    }

    "be able to get self" in {
      await(GestaltAccount.getSelf()).id must_== rootAccount.id
    }

    "be able to get self (a different account)" in {
      val token = TokenFactory.createToken(Some(rootDir.orgId), testAccount.id, 60, ACCESS_TOKEN, None).get
      await(GestaltAccount.getSelf()(tokenSdk.withCreds(
        GestaltBearerCredentials(OpaqueToken(token.id.asInstanceOf[UUID], GestaltToken.ACCESS_TOKEN).toString)
      ))).id must_== testAccount.id
    }

    "be listed among directory account listing" in {
      await(rootDir.listAccounts()) must containTheSameElementsAs(Seq(await(GestaltAccount.getById(rootAccount.id)).get,testAccount))
    }

    "return its created description" in {
      await(GestaltAccount.getById(testAccount.id)).get.description must beSome("some user")
    }

    "handle appropriately for non-existent lookup" in {
      await(rootOrg.getAccountById(UUID.randomUUID)) must beNone
    }

    "not be able to delete themselves" in {
      await(GestaltAccount.deleteAccount(rootAccount.id)) must throwA[BadRequestException](".*cannot delete self.*")
    }

    "not be able to disable themselves" in {
      await(keySdk.postJson(s"accounts/${rootAccount.id}/disable")).toString must throwA[BadRequestException](".*cannot disable self.*")
    }

    "disabled accounts" in {
      lazy val raccount = await(GestaltOrg.createAccount(rootOrg.id, GestaltAccountCreateWithRights(
        username = SecureIdGenerator.genId36(24),
        firstName = "", lastName = "",
        credential = GestaltPasswordCredential(SecureIdGenerator.genId36(24))
      )))
      lazy val GestaltAPIKey(key,secret,_,_) = await(raccount.generateAPICredentials(Some(rootOrg.id)))

      "cannot auth" in {
        AccountFactory.find(raccount.id) must beSome(beADisabledAccount.not)
        val newSDK = keySdk.withCreds(GestaltBasicCredentials(key, secret.get))
        await(keySdk.postJson(s"accounts/${raccount.id}/disable")).toString must /("disabled" -> true)
        AccountFactory.find(raccount.id) must beSome(beADisabledAccount)
        await(GestaltAccount.getSelf()(newSDK)) must throwA[UnauthorizedAPIException]
      }

      "cannot enable self" in {
        // because we can't auth, but still needs checking
        val newSDK = keySdk.withCreds(GestaltBasicCredentials(key, secret.get))
        AccountFactory.find(raccount.id) must beSome(beADisabledAccount)
        await(newSDK.postJson(s"accounts/${raccount.id}/enable")) must throwA[UnauthorizedAPIException]
        AccountFactory.find(raccount.id) must beSome(beADisabledAccount)
      }

      "can auth after being re-enabled" in {
        await(keySdk.postJson(s"accounts/${raccount.id}/enable")).toString must /("enabled" -> true)
        AccountFactory.find(raccount.id) must beSome(beADisabledAccount.not)
        val newSDK = keySdk.withCreds(GestaltBasicCredentials(key, secret.get))
        await(GestaltAccount.getSelf()(newSDK)).id must_== raccount.id
      }
    }

    "should 404 on enabling non-existent account" in {
      await(keySdk.postJson(s"accounts/${UUID.randomUUID()}/enable")) must throwA[ResourceNotFoundException]
    }

    "should 404 on disabling non-existent account" in {
      await(keySdk.postJson(s"accounts/${UUID.randomUUID()}/disable")) must throwA[ResourceNotFoundException]
    }

    "be updated in total using old GestaltAccountUpdate SDK" in {
      val updatedAccount = await(GestaltAccount.updateAccount(
        accountId = rootAccount.id,
        update = GestaltAccountUpdate(
          email = Some("updated-email@email.com"),
          phoneNumber = Some("+1.505.1234567")
        )
      ))
      updatedAccount.email must beSome("updated-email@email.com")
      updatedAccount.phoneNumber must beSome("+15051234567")
      await(GestaltAccount.getById(updatedAccount.id)) must beSome(updatedAccount)
    }

    "clear phone number and email address for empty values using old GestaltAccountUpdate SDK" in {
      val updatedAccount = await(GestaltAccount.updateAccount(
        accountId = rootAccount.id,
        update = GestaltAccountUpdate(
          email = Some(""),
          phoneNumber = Some("")
        )
      ))
      updatedAccount.email must beNone
      updatedAccount.phoneNumber must beNone
      await(GestaltAccount.getById(updatedAccount.id)) must beSome(updatedAccount)
    }

    "be updated with an email address" in {
      val updatedAccount = await(rootAccount.update(
        'email -> Json.toJson(rootEmail)
      ))
      updatedAccount.email must beSome(rootEmail)
      await(GestaltAccount.getById(updatedAccount.id)) must beSome(updatedAccount)
    }

    "be updated with a phone number" in {
      val updatedAccount = await(rootAccount.update(
        'phoneNumber -> Json.toJson(rootPhone)
      ))
      updatedAccount.phoneNumber must beSome("+15058675309")
      await(GestaltAccount.getById(updatedAccount.id)) must beSome(updatedAccount)
    }

    "reject an improperly formatted phone number on create" in {
      await(rootDir.createAccount(GestaltAccountCreate(
        username = "accountWithBadEmail",
        firstName = "bad",
        lastName = "account",
        phoneNumber = Some("867.5309"),
        credential = GestaltPasswordCredential("letmein"))
      )) must throwA[BadRequestException](".*phone number was not properly formatted.*")
    }

    "reject an improperly formatted phone number on update" in {
      await(rootAccount.update(
        'phoneNumber -> Json.toJson("867.5309")
      )) must throwA[BadRequestException](".*phone number was not properly formatted.*")
    }

    "throw on update for username conflict" in {
      await(testAccount.update(
        'username -> Json.toJson(rootAccount.username)
      )) must throwA[ConflictException](".*username already exists.*")
    }

    "throw on create for username conflict" in {
      await(rootDir.createAccount(GestaltAccountCreate(
        username = testAccount.username,
        firstName = "new",
        lastName = "account",
        email = Some("root@root"),
        phoneNumber = None,
        credential = GestaltPasswordCredential("letmein"))
      )) must throwA[ConflictException](".*username already exists.*")
    }

    "throw on update for email conflict" in {
      await(testAccount.update(
        'email -> Json.toJson(rootEmail)
      )) must throwA[ConflictException](".*email address already exists.*")
    }

    "throw on create for email conflict" in {
      await(rootDir.createAccount(GestaltAccountCreate(
        username = "newAccount",
        firstName = "new",
        lastName = "account",
        email = testAccount.email,
        credential = GestaltPasswordCredential("letmein"))
      )) must throwA[ConflictException](".*email address already exists.*")
    }

    "throw on update for phone number conflict" in {
      await(testAccount.update(
        'phoneNumber -> Json.toJson(rootPhone)
      )) must throwA[ConflictException](".*phone number already exists.*")
    }

    "throw on create for phone number conflict" in {
      await(rootDir.createAccount(GestaltAccountCreate(
        username = "newAccount",
        firstName = "new",
        lastName = "account",
        email = Some("newaccount@root"),
        phoneNumber = testAccount.phoneNumber,
        credential = GestaltPasswordCredential("letmein"))
      )) must throwA[ConflictException](".*phone number already exists.*")
    }

    "be updated with a new username" in {
      val updated = await(testAccount.update(
        'username -> Json.toJson("newUsername")
      ))
      updated.username must_== "newUsername"
    }

    "allow email removal" in {
      val updatedAccount = await(testAccount.deregisterEmail())
      updatedAccount.email must beNone
      await(GestaltAccount.getById(updatedAccount.id)) must beSome(updatedAccount)
    }

    "allow phone number removal" in {
      val updatedAccount = await(testAccount.deregisterPhoneNumber())
      updatedAccount.phoneNumber must beNone
      await(GestaltAccount.getById(updatedAccount.id)) must beSome(updatedAccount)
    }

  }

  step({
    server.stop()
  })
}
