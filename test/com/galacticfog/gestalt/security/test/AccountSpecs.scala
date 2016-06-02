package com.galacticfog.gestalt.security.test

import java.util.UUID

import com.galacticfog.gestalt.security.api.GestaltToken.ACCESS_TOKEN
import com.galacticfog.gestalt.security.api.{GestaltOrg, _}
import com.galacticfog.gestalt.security.api.errors.{BadRequestException, ConflictException}
import com.galacticfog.gestalt.security.api.json.JsonImports._
import com.galacticfog.gestalt.security.data.APIConversions._
import com.galacticfog.gestalt.security.data.domain._
import play.api.libs.json.Json

class AccountSpecs extends SpecWithSDK {

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
