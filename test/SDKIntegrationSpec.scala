import java.util.UUID

import com.galacticfog.gestalt.security.Global
import com.galacticfog.gestalt.security.api._
import com.galacticfog.gestalt.security.api.errors.{ConflictException, UnauthorizedAPIException, BadRequestException}
import com.galacticfog.gestalt.security.data.domain.{AccountFactory, AppFactory, OrgFactory, DirectoryFactory}
import com.galacticfog.gestalt.security.data.model.UserAccountRepository
import org.specs2.execute.{Results, Result}
import org.specs2.matcher.{MatchResult, Expectable, Matcher, ValueCheck, ValueChecks}
import org.specs2.specification.Fragments
import play.api.libs.json.Json
import play.api.libs.ws.WS
import play.api.test._
import com.galacticfog.gestalt.security.data.APIConversions._
import com.galacticfog.gestalt.security.api.json.JsonImports._

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

  step({
      server.start()
  })

  val client = WS.client(fakeApp)
  implicit val sdk: GestaltSecurityClient = GestaltSecurityClient(
    wsclient = client,
    protocol = HTTP,
    hostname = "localhost",
    port = testServerPort,
    apiKey = ru,
    apiSecret = rp
  )
  lazy val rootOrg: GestaltOrg = OrgFactory.getRootOrg().get
  lazy val rootDir: GestaltDirectory = await(rootOrg.listDirectories()).head
  lazy val daoRootDir = DirectoryFactory.listByOrgId(rootOrg.id).head
  lazy val rootAccount: GestaltAccount = daoRootDir.lookupAccountByUsername(ru).get
  val rootPhone = "+1.505.867.5309"
  val rootEmail = "root@root"

  "Service" should {

    "return OK on /health" in {
      await(client.url(s"http://localhost:${testServerPort}/health").get()).status must equalTo(OK)
    }

  }

  "Root org" should {

    "be accessible by name" in {
      val sdkRootMaybe = await(GestaltOrg.getByFQON("root"))
      sdkRootMaybe must beSome(rootOrg)
    }

    "be accessible by id" in {
      val sdkRootMaybe = await(GestaltOrg.getById(rootOrg.id))
      sdkRootMaybe must beSome(rootOrg)
    }

    "be accessible as current" in {
      val curOrg = await(GestaltOrg.getCurrentOrg)
      curOrg must_== rootOrg
    }

    "appear in list of all orgs" in {
      val orgs = await(GestaltOrg.listOrgs(ru, rp))
      orgs must contain(exactly(rootOrg))
    }

    "be syncable via root admin" in {
      val sync = await(GestaltOrg.syncOrgTree(None, ru, rp))
      sync.orgs     must contain(exactly(rootOrg))
      sync.accounts must contain(exactly(rootAccount))

      await(GestaltOrg.syncOrgTree(Some(rootOrg.id), ru, rp)) must_== sync
    }

    "perform framework authorization equivalently" in {
      // against implicit root org
      val auth1 = await(GestaltOrg.authorizeFrameworkUser(ru, rp))
      auth1 must beSome
      val ar = auth1.get
      // against explicit root org
      val auth2 = await(GestaltOrg.authorizeFrameworkUser(rootOrg.fqon, ru, rp))
      auth2 must beSome(ar)
      // against explicit org id
      val auth3 = await(GestaltOrg.authorizeFrameworkUser(rootOrg.id, ru, rp))
      auth3 must beSome(ar)
    }

    "not be capable of deletion" in {
      await(GestaltOrg.deleteOrg(rootOrg.id,ru,rp)) must throwA[BadRequestException](".*cannot delete root org.*")
    }

    "list root admin among accounts" in {
      await(rootOrg.listAccounts) must contain(exactly(rootAccount))
    }

    "get the root admin by username" in {
      await(rootOrg.getAccountByUsername(rootAccount.username)) must beSome(rootAccount)
    }

    "get the root account by account id" in {
      await(rootOrg.getAccountById(rootAccount.id)) must beSome(rootAccount)
    }

  }

  "Root dir" should {
    "contain the root account" in {
      await(rootDir.listAccounts()) must containTheSameElementsAs(Seq(rootAccount))
    }

    "be available by id" in {
      await(GestaltDirectory.getById(rootDir.id)) must beSome(rootDir)
    }
  }

  "Root app" should {

    lazy val rootApp: GestaltApp = AppFactory.findServiceAppForOrg(rootOrg.id).get
    lazy val appAuth = await(GestaltApp.authorizeUser(rootApp.id, GestaltBasicCredsToken(ru,rp))).get

    "be accessible from root org endpoint" in {
      await(rootOrg.getServiceApp) must_== rootApp
    }

    "show up in list of root org apps" in {
      val apps = await(rootOrg.listApps)
      apps must contain(exactly(rootApp))
    }

    "be accessible by id" in {
      await(GestaltApp.getById(rootApp.id)) must_== Some(rootApp)
    }

    "returns same rights as auth" in {
      await(GestaltApp.listAccountGrantsByUsername(rootApp.id, "root") ) must containTheSameElementsAs(appAuth.rights)
      await(GestaltApp.listAccountGrants(rootApp.id, appAuth.account.id)) must containTheSameElementsAs(appAuth.rights)
    }

    "returns the same accounts as the root org" in {
      await(rootApp.listAccounts) must containTheSameElementsAs(await(rootOrg.listAccounts))
    }

    "not be capable of deletion" in {
      await(GestaltApp.deleteApp(rootApp.id, ru, rp)) must throwA[BadRequestException](".*cannot delete service app.*")
    }

    "authenticate equivalently to framework" in {
      appAuth must_== await(GestaltOrg.authorizeFrameworkUser(ru, rp)).get
    }

    "get the root user by username" in {
      await(rootApp.getAccountByUsername(rootAccount.username)) must beSome(rootAccount)
    }

  }

  "Org naming constraints" should {

    "prohibit a mixed case name" in {
      val failure = GestaltOrg.createSubOrg(rootOrg.id, name = "hasAcapitalletter")
      await(failure) must throwA[BadRequestException](".*org name is invalid.*")
    }

    "prohibit a name with a space" in {
      val failure = GestaltOrg.createSubOrg(rootOrg.id, name = "has space")
      await(failure) must throwA[BadRequestException](".*org name is invalid.*")
    }

    "prohibit a name with preceding dash" in {
      val failure = GestaltOrg.createSubOrg(rootOrg.id, name = "-good-but-for-the-dash")
      await(failure) must throwA[BadRequestException](".*org name is invalid.*")
    }

    "prohibit a name with a trailing dash" in {
      val failure = GestaltOrg.createSubOrg(rootOrg.id, name = "good-but-for-the-dash-")
      await(failure) must throwA[BadRequestException](".*org name is invalid.*")
    }

    "prohibit a name with consecutive dash" in {
      val failure = GestaltOrg.createSubOrg(rootOrg.id, name = "good-but-for--the-dash")
      await(failure) must throwA[BadRequestException](".*org name is invalid.*")
    }

  }

  "Right grants" should {

    "not permit empty grant names" in {
      await(GestaltOrg.addGrantToAccount(
        orgId = rootOrg.id,
        accountId = rootAccount.id,
        grant = GestaltGrantCreate(
          grantName = ""
        )
      )) must throwA[BadRequestException](".*right grant must be non-empty without leading or trailing spaces.*")
    }

    "not permit spaces at front of grant names" in {
      await(GestaltOrg.addGrantToAccount(
        orgId = rootOrg.id,
        accountId = rootAccount.id,
        grant = GestaltGrantCreate(
          grantName = " not-trimmed"
        )
      )) must throwA[BadRequestException](".*right grant must be non-empty without leading or trailing spaces.*")
    }

    "not permit spaces at back of grant names" in {
      await(GestaltOrg.addGrantToAccount(
        orgId = rootOrg.id,
        accountId = rootAccount.id,
        grant = GestaltGrantCreate(
          grantName = "not-trimmed "
        )
      )) must throwA[BadRequestException](".*right grant must be non-empty without leading or trailing spaces.*")
    }

  }

  "New Org Without Directory" should {

    lazy val newOrgName = "new-org-no-dir"
    lazy val newOrg = await(rootOrg.createSubOrg(GestaltOrgCreate(
      name = newOrgName,
      createDefaultUserGroup = false
    )))
    lazy val newOrgApp = await(newOrg.getServiceApp())

    "not have root in fqon" in {
      newOrg must haveName(newOrgName)
      newOrg.fqon must_== newOrgName
    }

    "show up in the root org tree" in {
      await(rootOrg.listOrgs()) must containTheSameElementsAs(Seq(await(GestaltOrg.getById(rootOrg.id)).get,newOrg))
    }

    "be alone in its own org tree" in {
      await(newOrg.listOrgs()) must containTheSameElementsAs(Seq(newOrg))
    }

    "not contain a new directory" in {
      val orgDirs = await(newOrg.listDirectories(ru, rp))
      orgDirs must beEmpty
      val rootDirs = await(rootOrg.listDirectories(ru, rp))
      rootDirs must haveSize(1) // no new dirs
      val dir = rootDirs.head
      val groups = await(dir.listGroups)
      groups must haveSize(2) // dir has a new group
    }

    "allow creator to authenticate by name" in {
      val authAttempt = await(GestaltOrg.authorizeFrameworkUser(newOrg.fqon, username = ru, password = rp))
      authAttempt must beSome
      val auth = authAttempt.get
      auth.account must_== rootAccount
      auth.groups must haveSize(2)
      auth.orgId must_== newOrg.id
      auth.rights must not beEmpty
    }

    "contain a service app" in {
      newOrgApp.isServiceApp must beTrue
      newOrgApp.orgId must_== newOrg.id
    }

    "be unable to create another org with the same name" in {
      await(rootOrg.createSubOrg(GestaltOrgCreate(
        name = newOrgName,
        createDefaultUserGroup = false
      ))) must throwA[ConflictException](".*name already exists.*")
    }

    lazy val newOrgMappings = await(newOrgApp.listAccountStores)
    lazy val newOrgMapping  = newOrgMappings.head
    lazy val newOrgAdminGroup = await(GestaltGroup.getById(newOrgMapping.storeId))

    "contain an account store mapping to a group in the parent directory" in {
      newOrgMappings must haveSize(1)
      newOrgMapping.storeType must_== com.galacticfog.gestalt.security.api.GROUP
      newOrgAdminGroup must beSome

      await(GestaltAccountStoreMapping.getById(newOrgMapping.id)) must beSome(newOrgMapping)
    }

    "result in a new group membership for the creating account" in {
      val rootGroups = await(rootAccount.listGroupMemberships())
      rootGroups must contain(newOrgAdminGroup.get)
      rootGroups must haveSize(2)
    }

    "list equivalent org and service app mappings" in {
      await(newOrgApp.listAccountStores()) must containTheSameElementsAs(newOrgMappings)
    }

    "list the new group as an org group" in {
      await(newOrg.listGroups()) must contain(exactly(newOrgAdminGroup.get))
    }

    "list equivalent org and service app groups" in {
      val fromOrg = await(newOrg.listGroups())
      val fromApp = await(newOrgApp.listGroups())
      fromOrg must containTheSameElementsAs(fromApp)
    }

    "list the root admin as an org account" in {
      await(newOrg.listAccounts()) must contain(exactly(rootAccount))
    }

    "get the groups for the root admin" in {
      await(rootAccount.listGroupMemberships()) must containTheSameElementsAs(await(rootDir.listGroups()))
    }

    "verify right grants to the root admin via the new org admin group" in {
      val accountRights = await(newOrg.listAccountGrants(rootAccount.id))
      val groupRights   = await(newOrg.listGroupGrants(newOrgAdminGroup.get.id))
      accountRights must containTheSameElementsAs(groupRights)
    }

    "list equivalent account rights from service app and org" in {
      val fromOrg = await(newOrg.listAccountGrants(rootAccount.id))
      val fromApp = await(newOrgApp.listAccountGrants(rootAccount.id))
      fromOrg must containTheSameElementsAs(fromApp)
    }

    "list equivalent group rights from service app and org" in {
      val fromOrg = await(newOrg.listGroupGrants(newOrgAdminGroup.get.id))
      val fromApp = await(newOrgApp.listGroupGrants(newOrgAdminGroup.get.id))
      fromOrg must containTheSameElementsAs(fromApp)
    }

    "be unable to get the root admin by name via the org because there is no default account store" in {
      await(newOrg.getAccountByUsername(rootAccount.username)) must throwA[BadRequestException](".*does not have a default account store.*")
    }

    "get the new group by id via the org" in {
      await(newOrg.getGroupById(newOrgAdminGroup.get.id)) must beSome(newOrgAdminGroup.get)
    }

    "get the new group by the name in the directory" in {
      await(rootDir.getGroupByName(newOrgAdminGroup.get.name)) must beSome(newOrgAdminGroup.get)
    }

    "be unable to get the new group by name via the org because there is no default group store" in {
      await(newOrg.getGroupByName(newOrgAdminGroup.get.name)) must throwA[BadRequestException](".*does not have a default group store.*")
    }

    "include the root admin in the new org admin group" in {
      await(newOrgAdminGroup.get.listAccounts) must contain(rootAccount)
    }

    "allow creator to delete org" in {
      await(GestaltOrg.deleteOrg(newOrg.id)) must beTrue
    }

    "not exist after deletion" in {
      await(GestaltOrg.getByFQON(newOrgName)) must throwA[UnauthorizedAPIException]
    }

    "automatically remove admin group on deletion" in {
      val rootDirGroups = await(rootOrg.listDirectories flatMap {_.head.listGroups})
      rootDirGroups must haveSize(1)
    }

  }

  lazy val testAccount = await(rootDir.createAccount(GestaltAccountCreate(
    username = "test",
    firstName = "test",
    lastName = "user",
    email = "test@root",
    phoneNumber = "+1.555.555.5555",
    credential = GestaltPasswordCredential("letmein")
  )))

  "Accounts" should {

    "be listed in dirs by username" in {
      await(rootDir.getAccountByUsername(testAccount.username)) must beSome(testAccount)
    }

    "be listed among directory account listing" in {
      await(rootDir.listAccounts()) must containTheSameElementsAs(Seq(await(GestaltAccount.getById(rootAccount.id)).get,testAccount))
    }

    "handle appropriately for non-existent lookup" in {
      await(rootOrg.getAccountById(UUID.randomUUID)) must beNone
    }

    "not be able to delete themselves" in {
      await(GestaltAccount.deleteAccount(rootAccount.id, ru, rp)) must throwA[BadRequestException](".*cannot delete self.*")
    }

    "be updated with an email address" in {
      val updatedAccount = await(rootAccount.update(
        'email -> Json.toJson(rootEmail)
      ))
      updatedAccount.email must_== rootEmail
      await(GestaltAccount.getById(updatedAccount.id)) must beSome(updatedAccount)
    }

    "be updated with a phone number" in {
      val updatedAccount = await(rootAccount.update(
        'phoneNumber -> Json.toJson(rootPhone)
      ))
      updatedAccount.phoneNumber must_== "+15058675309"
      await(GestaltAccount.getById(updatedAccount.id)) must beSome(updatedAccount)
    }

    "reject an improperly formatted phone number on create" in {
      await(rootDir.createAccount(GestaltAccountCreate(
        username = "accountWithBadEmail",
        firstName = "bad",
        lastName = "account",
        email = "",
        phoneNumber = "867.5309",
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
        email = "root@root",
        phoneNumber = "",
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
        phoneNumber = "",
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
        email = "newaccount@root",
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
      updatedAccount.email must_== ""
      await(GestaltAccount.getById(updatedAccount.id)) must beSome(updatedAccount)
    }

    "allow phone number removal" in {
      val updatedAccount = await(testAccount.deregisterPhoneNumber())
      updatedAccount.phoneNumber must_== ""
      await(GestaltAccount.getById(updatedAccount.id)) must beSome(updatedAccount)
    }

  }

  lazy val testGroup2 = await(rootDir.getGroupByName("testGroup2")).get
  lazy val testUser2 = await(rootDir.getAccountByUsername("testAccount2")).get

  "Directory" should {

    "allow a new group to be created" in {
      await(rootDir.createGroup(GestaltGroupCreate("testGroup2"))) must haveName("testGroup2")
    }

    "allow a new user to be created in the group" in {
      await(rootDir.createAccount(GestaltAccountCreate(
        username = "testAccount2",
        firstName = "test",
        lastName = "account2",
        email = "", phoneNumber = "",
        groups = Some(Seq(testGroup2.id)),
        credential = GestaltPasswordCredential(password = "letmein")
      ))) must haveName("testAccount2")
    }

    "list the group in the account memberships" in {
      await(testUser2.listGroupMemberships()) must containTheSameElementsAs(Seq(testGroup2))
    }

    "allow accounts to be added to groups after creation" in {
      val testGroup3 = await(rootDir.createGroup(GestaltGroupCreate("testGroup3")))
      val newMemberships = await(testGroup3.updateMembership(add = Seq(testUser2.id)))
      newMemberships must contain(exactly(testUser2))
      await(testUser2.listGroupMemberships()) must containTheSameElementsAs(Seq(testGroup2, testGroup3))
    }

//    "allow account to authenticate against an org via a manually added group" in { // TODO
//
//    }

    "process group deletion" in {
      await(GestaltGroup.deleteGroup(testGroup2.id, ru, rp)) must beTrue
      await(GestaltGroup.getById(testGroup2.id)) must throwA[UnauthorizedAPIException]
      await(rootDir.getGroupByName("testGroup2")) must beNone
      await(testUser2.listGroupMemberships()) must not contain(hasName("testGroup2"))
    }

    "process account deletion" in {
      await(GestaltAccount.deleteAccount(testUser2.id, ru, rp)) must beTrue
      await(GestaltAccount.getById(testUser2.id)) must beSome(testUser2)
      await(rootDir.getAccountByUsername("testAccount2")) must beSome(testUser2)
    }

    // TODO: this requires an app or org to test against, which worked before
//    "disable account for auth" in {
//
//    }

  }

  "New Org with Directory" should {

    lazy val newOrgName = "new-org-with-dir"
    lazy val newOrg = await(rootOrg.createSubOrg(GestaltOrgCreate(
      name = newOrgName,
      createDefaultUserGroup = true
    )))
    lazy val newOrgApp = await(newOrg.getServiceApp())
    lazy val newOrgDir = await(newOrg.listDirectories()).head

    "have a new directory" in {
      await(newOrg.listDirectories()) must haveSize(1)
    }

    "have a mapping for the new directory as the default group store" in {
      await(newOrgApp.listAccountStores()) must contain( (asm: GestaltAccountStoreMapping) =>
          asm.storeId == newOrgDir.id
            && asm.storeType == DIRECTORY
            && asm.isDefaultGroupStore
      )
    }

    "have a new group in the new directory with a procedural name with a mapping as the default account store" in {
      val dirGroups = await(newOrgDir.listGroups)
      dirGroups must haveSize(1)
      val newGroup = dirGroups.head
      newGroup.name must contain(newOrg.name)
      await(newOrgApp.listAccountStores()) must contain( (asm: GestaltAccountStoreMapping) =>
          asm.storeId == newGroup.id
            && asm.storeType == GROUP
            && asm.isDefaultAccountStore
      )
    }

    "have a group in the root directory for the root user, visible by ID but not by name" in {
      val rootGroupSeq = await(newOrg.listGroups).filter(_.directoryId == rootDir.id)
      rootGroupSeq must haveSize(1)
      val rootGroup = rootGroupSeq.head
      await(newOrg.getGroupById(rootGroup.id)) must beSome(rootGroup)
      await(newOrg.getGroupByName(rootGroup.name)) must beNone
    }

    "should list groups created directly in the directory" in {
      val manualGrp = await(newOrgDir.createGroup(GestaltGroupCreate(
        name = "manually-created-dir-group"
      )))
      await(newOrg.listGroups) must contain(manualGrp)
      await(newOrg.getGroupById(manualGrp.id)) must beSome(manualGrp)
      await(newOrg.getGroupByName(manualGrp.name)) must beSome(manualGrp)
    }

    "should list accounts created directly in the directory" in {
      val manualAccount = await(newOrgDir.createAccount(GestaltAccountCreate(
        username = "manual-user",
        firstName = "Manny",
        lastName = "User",
        email = "",
        phoneNumber = "",
        groups = None,
        credential = GestaltPasswordCredential("letmein")
      )))
      await(newOrg.listAccounts) must contain(manualAccount)
      await(newOrg.getAccountById(manualAccount.id)) must beSome(manualAccount)
      await(newOrg.getAccountByUsername(manualAccount.username)) must beSome(manualAccount)
    }

    "should not add account in the case of a non-existent group" in {
      val failAccountName = "failedAccount"
      await(GestaltOrg.createAccount(newOrg.id, GestaltAccountCreateWithRights(
        username = failAccountName,
        firstName = "Will",
        lastName = "Fail",
        email = "",
        phoneNumber = "",
        credential = GestaltPasswordCredential("letmein"),
        groups = Some(Seq(UUID.randomUUID())), // failure
        rights = None
      ))) must throwA[BadRequestException](".*cannot add account to non-existent group.*")
      await(newOrg.listAccounts) must not contain((a: GestaltAccount) => a.username == failAccountName)
    }

    "should not add account with invalid grant name" in {
      val failAccountName = "failedAccount"
      await(GestaltOrg.createAccount(newOrg.id, GestaltAccountCreateWithRights(
        username = failAccountName,
        firstName = "Will",
        lastName = "Fail",
        email = "",
        phoneNumber = "",
        credential = GestaltPasswordCredential("letmein"),
        groups = None,
        rights = Some(Seq(GestaltGrantCreate(
          grantName = ""   // fail
        ))
      )))) must throwA[BadRequestException](".*right grant must be non-empty without leading or trailing spaces.*")
      await(newOrg.listAccounts) must not contain((a: GestaltAccount) => a.username == failAccountName)
    }

    "should not add group with invalid right grants" in {
      val failGroupName = "failedGroup"
      await(GestaltOrg.createGroup( newOrg.id, GestaltGroupCreateWithRights(
        name = failGroupName,
        rights = Some(Seq(GestaltGrantCreate(
          grantName = "" // fail
        )))
      ))) must throwA[BadRequestException](".*right grant must be non-empty without leading or trailing spaces.*")
      await(newOrg.listGroups) must not contain((g: GestaltGroup) => g.name == failGroupName)
    }

    "cleanup" in {
      await(GestaltOrg.deleteOrg(newOrg.id)) must beTrue
    }

  }

  "Org Groups" should {

    lazy val newOrgName = "new-org-for-org-group-testing"
    lazy val newOrg = await(rootOrg.createSubOrg(GestaltOrgCreate(
      name = newOrgName,
      createDefaultUserGroup = true
    )))
    lazy val newOrgApp = await(newOrg.getServiceApp())
    lazy val newOrgDir = await(newOrg.listDirectories()).head
    lazy val unmappedGrpFromRootDir = await(rootDir.createGroup(GestaltGroupCreate(
      name = "unmapped-group-in-root-dir"
    )))

    "precheck" in {
      await(rootDir.getGroupByName(unmappedGrpFromRootDir.name)) must beSome(unmappedGrpFromRootDir)
      await(GestaltGroup.getById(unmappedGrpFromRootDir.id)) must beSome(unmappedGrpFromRootDir)
      await(rootDir.listGroups) must contain(unmappedGrpFromRootDir)
    }

    "not list an unmapped group in the org" in {
      await(newOrg.listGroups) must not contain unmappedGrpFromRootDir
    }

    "not list an unmapped group in the service app" in {
      await(newOrgApp.listGroups) must not contain unmappedGrpFromRootDir
    }

    "fail to get an unmapped group by id in the org" in {
      await(newOrg.getGroupById(unmappedGrpFromRootDir.id)) must beNone
    }

    "fail to get an unmapped group by id in the service app" in {
      await(newOrgApp.getGroupById(unmappedGrpFromRootDir.id)) must beNone
    }

    "fail to get an unmapped group by name in the org" in {
      await(newOrg.getGroupByName(unmappedGrpFromRootDir.name)) must beNone
    }

    "fail to get an unmapped group by name in the service app" in {
      await(newOrgApp.getGroupByName(unmappedGrpFromRootDir.name)) must beNone
    }

    "successfully list/get in the org/app after being mapped to the org" in {
      val mapping = await(newOrg.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "test-mapping",
        description = "",
        storeType = GROUP,
        accountStoreId = unmappedGrpFromRootDir.id,
        isDefaultAccountStore = false,
        isDefaultGroupStore = false
      )))
      await(newOrg.listAccountStores) must contain( (asm: GestaltAccountStoreMapping) => asm.id == mapping.id )
      await(newOrgApp.listAccountStores) must contain( (asm: GestaltAccountStoreMapping) => asm.id == mapping.id )
      await(newOrg.listGroups) must contain(unmappedGrpFromRootDir)
      await(newOrgApp.listGroups) must contain(unmappedGrpFromRootDir)
      await(newOrg.getGroupById(unmappedGrpFromRootDir.id)) must beSome(unmappedGrpFromRootDir)
      await(newOrgApp.getGroupById(unmappedGrpFromRootDir.id)) must beSome(unmappedGrpFromRootDir)
      await(newOrg.getGroupByName(unmappedGrpFromRootDir.name)) must beNone // not in the default group store
      await(newOrgApp.getGroupByName(unmappedGrpFromRootDir.name)) must beNone // not in the default group store
      await(mapping.delete) must beTrue
      await(GestaltAccountStoreMapping.getById(mapping.id)) must throwA[UnauthorizedAPIException]
      await(newOrg.listAccountStores) must not contain( (asm: GestaltAccountStoreMapping) => asm.id == mapping.id )
      await(newOrgApp.listAccountStores) must not contain( (asm: GestaltAccountStoreMapping) => asm.id == mapping.id )
    }

    "successfully list/get in the org/app after being mapped to the service app" in {
      val mapping = await(newOrgApp.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "test-mapping",
        description = "",
        storeType = GROUP,
        accountStoreId = unmappedGrpFromRootDir.id,
        isDefaultAccountStore = false,
        isDefaultGroupStore = false
      )))
      await(newOrg.listAccountStores) must contain( (asm: GestaltAccountStoreMapping) => asm.id == mapping.id )
      await(newOrgApp.listAccountStores) must contain( (asm: GestaltAccountStoreMapping) => asm.id == mapping.id )
      await(newOrg.listGroups) must contain(unmappedGrpFromRootDir)
      await(newOrgApp.listGroups) must contain(unmappedGrpFromRootDir)
      await(newOrg.getGroupById(unmappedGrpFromRootDir.id)) must beSome(unmappedGrpFromRootDir)
      await(newOrgApp.getGroupById(unmappedGrpFromRootDir.id)) must beSome(unmappedGrpFromRootDir)
      await(newOrg.getGroupByName(unmappedGrpFromRootDir.name)) must beNone // not in the default group store
      await(newOrgApp.getGroupByName(unmappedGrpFromRootDir.name)) must beNone // not in the default group store
      await(mapping.delete) must beTrue
      await(GestaltAccountStoreMapping.getById(mapping.id)) must throwA[UnauthorizedAPIException]
      await(newOrg.listAccountStores) must not contain( (asm: GestaltAccountStoreMapping) => asm.id == mapping.id )
      await(newOrgApp.listAccountStores) must not contain( (asm: GestaltAccountStoreMapping) => asm.id == mapping.id )
    }

    lazy val newOrgGrp = await(GestaltOrg.createGroup(newOrg.id, GestaltGroupCreateWithRights(
      name = "new-org-group",
      rights = Some(Seq(GestaltGrantCreate(
        grantName = "grantA"
      ), GestaltGrantCreate(
        grantName = "grantB", grantValue = Some("grantBvalue")
      )))
    )))

    "show up in org after creation" in {
      await(newOrg.getGroupById(newOrgGrp.id)) must beSome(newOrgGrp)
      await(newOrg.getGroupByName(newOrgGrp.name)) must beSome(newOrgGrp)
      await(newOrg.listGroups) must contain(newOrgGrp)
    }

    "list grants provided at creation" in {
      await(GestaltOrg.listGroupGrants(newOrg.id, newOrgGrp.id)) must containTheSameElementsAs(Seq(
        GestaltRightGrant(null, "grantA", None, newOrgApp.id),
        GestaltRightGrant(null, "grantB", Some("grantBvalue"), newOrgApp.id)
      ), (a: GestaltRightGrant,b: GestaltRightGrant) => (a.grantName == b.grantName && a.grantValue == b.grantValue && a.appId == b.appId))
    }


    "cleanup" in {
      await(GestaltGroup.deleteGroup(unmappedGrpFromRootDir.id,ru,rp)) must beTrue
      await(GestaltOrg.deleteOrg(newOrg.id)) must beTrue
    }

  }

  "Account Store Mappings" should {

    lazy val testDirInRootOrg = await(rootOrg.createDirectory(GestaltDirectoryCreate(
      name = "test-dir-in-root-org",
      description = None,
      config = None
    )))
    lazy val testGroupInTestDir = await(testDirInRootOrg.createGroup(GestaltGroupCreate(
      name = "test-group-in-test-dir"
    )))
    lazy val testGroup2InTestDir = await(testDirInRootOrg.createGroup(GestaltGroupCreate(
      name = "test-group2-in-test-dir"
    )))
    lazy val testSubOrg = await(rootOrg.createSubOrg(GestaltOrgCreate(
      name = "suborg-for-asm-testing",
      createDefaultUserGroup = false
    )))
    lazy val testSubOrgApp = await(testSubOrg.getServiceApp)

    "fail appropriately for non-existent directory store on org" in {
      await(testSubOrg.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "failed-mapping",
        description = "",
        storeType = DIRECTORY,
        accountStoreId = UUID.randomUUID(),
      isDefaultAccountStore = false,
      isDefaultGroupStore = false
      ))) must throwA[BadRequestException](".*account store does not correspond to an existing directory.*")
    }

    "fail appropriately for non-existent directory store on app" in {
      await(testSubOrgApp.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "failed-mapping",
        description = "",
        storeType = DIRECTORY,
        accountStoreId = UUID.randomUUID(),
        isDefaultAccountStore = false,
        isDefaultGroupStore = false
      ))) must throwA[BadRequestException](".*account store does not correspond to an existing directory.*")
    }

    "fail appropriately for non-existent group store on org" in {
      await(testSubOrg.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "failed-mapping",
        description = "",
        storeType = GROUP,
        accountStoreId = UUID.randomUUID(),
        isDefaultAccountStore = false,
        isDefaultGroupStore = false
      ))) must throwA[BadRequestException](".*account store does not correspond to an existing group.*")
    }

    "fail appropriately for non-existent group store on app" in {
      await(testSubOrgApp.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "failed-mapping",
        description = "",
        storeType = GROUP,
        accountStoreId = UUID.randomUUID(),
        isDefaultAccountStore = false,
        isDefaultGroupStore = false
      ))) must throwA[BadRequestException](".*account store does not correspond to an existing group.*")
    }

    "fail appropriately when setting group as default group store in org" in {
      await(testSubOrg.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "failed-mapping",
        description = "",
        storeType = GROUP,
        accountStoreId = testGroupInTestDir.id,
        isDefaultAccountStore = false,
        isDefaultGroupStore = true
      ))) must throwA[BadRequestException](".*default group store must be an account store of type DIRECTORY.*")
    }

    "fail appropriately when setting group as default group store in app" in {
      await(testSubOrgApp.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "failed-mapping",
        description = "",
        storeType = GROUP,
        accountStoreId = testGroupInTestDir.id,
        isDefaultAccountStore = false,
        isDefaultGroupStore = true
      ))) must throwA[BadRequestException](".*default group store must be an account store of type DIRECTORY.*")
    }

    "fail appropriately for redundant dir mapping against org" in {
      val newMapping = await(testSubOrg.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "first-mapping",
        description = "",
        storeType = DIRECTORY,
        accountStoreId = testDirInRootOrg.id,
        isDefaultAccountStore = false,
        isDefaultGroupStore = false
      )))
      await(testSubOrg.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "second-mapping",
        description = "",
        storeType = DIRECTORY,
        accountStoreId = testDirInRootOrg.id,
        isDefaultAccountStore = false,
        isDefaultGroupStore = false
      ))) must throwA[ConflictException](".*mapping already exists.*")
      await(GestaltAccountStoreMapping.delete(newMapping.id)) must beTrue
    }

    "fail appropriately for redundant dir mapping against app" in {
      val newMapping = await(testSubOrgApp.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "first-mapping",
        description = "",
        storeType = DIRECTORY,
        accountStoreId = testDirInRootOrg.id,
        isDefaultAccountStore = false,
        isDefaultGroupStore = false
      )))
      await(testSubOrgApp.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "second-mapping",
        description = "",
        storeType = DIRECTORY,
        accountStoreId = testDirInRootOrg.id,
        isDefaultAccountStore = false,
        isDefaultGroupStore = false
      ))) must throwA[ConflictException](".*mapping already exists.*")
      await(GestaltAccountStoreMapping.delete(newMapping.id)) must beTrue
    }

    "fail appropriately for redundant group mapping against org" in {
      val newMapping = await(testSubOrg.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "first-mapping",
        description = "",
        storeType = GROUP,
        accountStoreId = testGroupInTestDir.id,
        isDefaultAccountStore = false,
        isDefaultGroupStore = false
      )))
      await(testSubOrg.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "second-mapping",
        description = "",
        storeType = GROUP,
        accountStoreId = testGroupInTestDir.id,
        isDefaultAccountStore = false,
        isDefaultGroupStore = false
      ))) must throwA[ConflictException](".*mapping already exists.*")
      await(GestaltAccountStoreMapping.delete(newMapping.id)) must beTrue
    }

    "fail appropriately for redundant group mapping against app" in {
      val newMapping = await(testSubOrgApp.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "first-mapping",
        description = "",
        storeType = GROUP,
        accountStoreId = testGroupInTestDir.id,
        isDefaultAccountStore = false,
        isDefaultGroupStore = false
      )))
      await(testSubOrgApp.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "second-mapping",
        description = "",
        storeType = GROUP,
        accountStoreId = testGroupInTestDir.id,
        isDefaultAccountStore = false,
        isDefaultGroupStore = false
      ))) must throwA[ConflictException](".*mapping already exists.*")
      await(GestaltAccountStoreMapping.delete(newMapping.id)) must beTrue
    }

    "fail appropriately when setting conflicting default account store on app" in {
      val firstMapping = await(testSubOrgApp.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "first-default-account-store-mapping",
        description = "",
        storeType = GROUP,
        accountStoreId = testGroupInTestDir.id,
        isDefaultAccountStore = true,
        isDefaultGroupStore = false
      )))
      await(testSubOrgApp.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "conflicting-default-account-store-mapping",
        description = "",
        storeType = GROUP,
        accountStoreId = testGroup2InTestDir.id,
        isDefaultAccountStore = true,
        isDefaultGroupStore = false
      ))) must throwA[ConflictException](".*default account store already set.*")
      await(GestaltAccountStoreMapping.delete(firstMapping.id)) must beTrue
      await(testSubOrgApp.listAccountStores) must not contain(
        (asm: GestaltAccountStoreMapping) => asm.storeId == testGroup2InTestDir.id
      )
    }

    "fail appropriately when setting conflicting default account store on org" in {
      val firstMapping = await(testSubOrg.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "first-default-account-store-mapping",
        description = "",
        storeType = GROUP,
        accountStoreId = testGroupInTestDir.id,
        isDefaultAccountStore = true,
        isDefaultGroupStore = false
      )))
      await(testSubOrg.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "conflicting-default-account-store-mapping",
        description = "",
        storeType = GROUP,
        accountStoreId = testGroup2InTestDir.id,
        isDefaultAccountStore = true,
        isDefaultGroupStore = false
      ))) must throwA[ConflictException](".*default account store already set.*")
      await(GestaltAccountStoreMapping.delete(firstMapping.id)) must beTrue
      await(testSubOrgApp.listAccountStores) must not contain(
        (asm: GestaltAccountStoreMapping) => asm.storeId == testGroup2InTestDir.id
      )
    }

    "fail appropriately when setting conflicting default group store on org" in {
      val firstMapping = await(testSubOrg.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "first-default-group-store-mapping",
        description = "",
        storeType = DIRECTORY,
        accountStoreId = testDirInRootOrg.id,
        isDefaultAccountStore = false,
        isDefaultGroupStore = true
      )))
      await(testSubOrg.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "conflicting-default-group-store-mapping",
        description = "",
        storeType = DIRECTORY,
        accountStoreId = rootDir.id,
        isDefaultAccountStore = false,
        isDefaultGroupStore = true
      ))) must throwA[ConflictException](".*default group store already set.*")
      await(GestaltAccountStoreMapping.delete(firstMapping.id)) must beTrue
      await(testSubOrgApp.listAccountStores) must not contain(
        (asm: GestaltAccountStoreMapping) => asm.storeId == testGroup2InTestDir.id
      )
    }

    "fail appropriately when setting conflicting default group store on app" in {
      val firstMapping = await(testSubOrgApp.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "first-default-group-store-mapping",
        description = "",
        storeType = DIRECTORY,
        accountStoreId = testDirInRootOrg.id,
        isDefaultAccountStore = false,
        isDefaultGroupStore = true
      )))
      await(testSubOrgApp.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "conflicting-default-group-store-mapping",
        description = "",
        storeType = DIRECTORY,
        accountStoreId = rootDir.id,
        isDefaultAccountStore = false,
        isDefaultGroupStore = true
      ))) must throwA[ConflictException](".*default group store already set.*")
      await(GestaltAccountStoreMapping.delete(firstMapping.id)) must beTrue
      await(testSubOrgApp.listAccountStores) must not contain(
        (asm: GestaltAccountStoreMapping) => asm.storeId == testGroup2InTestDir.id
      )
    }

    "cleanup" in {
      await(GestaltDirectory.deleteDirectory(testDirInRootOrg.id)) must beTrue
      await(GestaltOrg.deleteOrg(testSubOrg.id)) must beTrue
    }

  }

  "Org Accounts" should {

    lazy val newOrgName = "new-org-for-org-account-testing"
    lazy val newOrg = await(rootOrg.createSubOrg(GestaltOrgCreate(
      name = newOrgName,
      createDefaultUserGroup = true
    )))
    lazy val newOrgApp = await(newOrg.getServiceApp())
    lazy val newOrgDir = await(newOrg.listDirectories()).head
    lazy val unmappedGrpFromRootDir = await(rootDir.createGroup(GestaltGroupCreate(
      name = "unmapped-group-in-root-dir"
    )))
    lazy val unmappedActFromRootDir = await(rootDir.createAccount(GestaltAccountCreate(
      username = "unmapped",
      firstName = "Bob",
      lastName = "Not-Mapped",
      email = "",
      phoneNumber = "",
      groups = Some(Seq(unmappedGrpFromRootDir.id)), // so we can map it below
      credential = GestaltPasswordCredential("letmein")
    )))

    "precheck" in {
      await(rootDir.getGroupByName(unmappedGrpFromRootDir.name)) must beSome(unmappedGrpFromRootDir)
      await(GestaltGroup.getById(unmappedGrpFromRootDir.id)) must beSome(unmappedGrpFromRootDir)
      await(rootDir.listGroups) must contain(unmappedGrpFromRootDir)
      await(rootDir.getAccountByUsername(unmappedActFromRootDir.username)) must beSome(unmappedActFromRootDir)
      await(GestaltAccount.getById(unmappedActFromRootDir.id)) must beSome(unmappedActFromRootDir)
      await(rootDir.listAccounts) must contain(unmappedActFromRootDir)
      await(unmappedGrpFromRootDir.listAccounts) must containTheSameElementsAs(Seq(unmappedActFromRootDir))
    }

    "not list an unmapped account in the org" in {
      await(newOrg.listAccounts) must not contain unmappedActFromRootDir
    }

    "not list an unmapped account in the service app" in {
      await(newOrgApp.listAccounts) must not contain unmappedActFromRootDir
    }

    "fail to get an unmapped account by id in the org" in {
      await(newOrg.getAccountById(unmappedActFromRootDir.id)) must beNone
    }

    "fail to get an unmapped account by id in the service app" in {
      await(newOrgApp.getAccountById(unmappedActFromRootDir.id)) must beNone
    }

    "fail to get an unmapped account by name in the org" in {
      await(newOrg.getAccountByUsername(unmappedActFromRootDir.name)) must beNone
    }

    "fail to get an unmapped account by name in the service app" in {
      await(newOrgApp.getAccountByUsername(unmappedActFromRootDir.name)) must beNone
    }

    "successfully list/get in the org/app after being mapped to the org" in {
      val mapping = await(newOrg.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "test-mapping",
        description = "",
        storeType = GROUP,
        accountStoreId = unmappedGrpFromRootDir.id,
        isDefaultAccountStore = false,
        isDefaultGroupStore = false
      )))
      await(newOrg.listAccountStores) must contain( (asm: GestaltAccountStoreMapping) => asm.id == mapping.id )
      await(newOrgApp.listAccountStores) must contain( (asm: GestaltAccountStoreMapping) => asm.id == mapping.id )
      await(newOrg.listAccounts) must contain(unmappedActFromRootDir)
      await(newOrgApp.listAccounts) must contain(unmappedActFromRootDir)
      await(newOrg.getAccountById(unmappedActFromRootDir.id)) must beSome(unmappedActFromRootDir)
      await(newOrgApp.getAccountById(unmappedActFromRootDir.id)) must beSome(unmappedActFromRootDir)
      await(newOrg.getAccountByUsername(unmappedActFromRootDir.name)) must beNone // because not default dir
      await(newOrgApp.getAccountByUsername(unmappedActFromRootDir.name)) must beNone // because not in default dir
      await(mapping.delete) must beTrue
      await(GestaltAccountStoreMapping.getById(mapping.id)) must throwA[UnauthorizedAPIException]
      await(newOrg.listAccountStores) must not contain( (asm: GestaltAccountStoreMapping) => asm.id == mapping.id )
      await(newOrgApp.listAccountStores) must not contain( (asm: GestaltAccountStoreMapping) => asm.id == mapping.id )
    }

    "successfully list/get in the org/app after being mapped to the org app" in {
      val mapping = await(newOrgApp.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "test-mapping",
        description = "",
        storeType = GROUP,
        accountStoreId = unmappedGrpFromRootDir.id,
        isDefaultAccountStore = false,
        isDefaultGroupStore = false
      )))
      await(newOrg.listAccountStores) must contain( (asm: GestaltAccountStoreMapping) => asm.id == mapping.id )
      await(newOrgApp.listAccountStores) must contain( (asm: GestaltAccountStoreMapping) => asm.id == mapping.id )
      await(newOrg.listAccounts) must contain(unmappedActFromRootDir)
      await(newOrgApp.listAccounts) must contain(unmappedActFromRootDir)
      await(newOrg.getAccountById(unmappedActFromRootDir.id)) must beSome(unmappedActFromRootDir)
      await(newOrgApp.getAccountById(unmappedActFromRootDir.id)) must beSome(unmappedActFromRootDir)
      await(newOrg.getAccountByUsername(unmappedActFromRootDir.name)) must beNone // because not default dir
      await(newOrgApp.getAccountByUsername(unmappedActFromRootDir.name)) must beNone // because not in default dir
      await(mapping.delete) must beTrue
      await(GestaltAccountStoreMapping.getById(mapping.id)) must throwA[UnauthorizedAPIException]
      await(newOrg.listAccountStores) must not contain( (asm: GestaltAccountStoreMapping) => asm.id == mapping.id )
      await(newOrgApp.listAccountStores) must not contain( (asm: GestaltAccountStoreMapping) => asm.id == mapping.id )
    }

    lazy val newOrgAct = await(GestaltOrg.createAccount(newOrg.id, GestaltAccountCreateWithRights(
      username = "new-org-account",
      firstName = "Account",
      lastName = "InNewOrg",
      email = "",
      phoneNumber = "",
      groups = None,
      rights = Some(Seq(GestaltGrantCreate(
        grantName = "grantA"
      ), GestaltGrantCreate(
        grantName = "grantB", grantValue = Some("grantBvalue")
      ))),
      credential = GestaltPasswordCredential("letmein")
    )))

    "show up in org after creation" in {
      await(newOrg.getAccountById(newOrgAct.id)) must beSome(newOrgAct)
      await(newOrg.getAccountByUsername(newOrgAct.name)) must beSome(newOrgAct)
      await(newOrg.listAccounts) must contain(newOrgAct)
    }

    "list grants provided at creation" in {
      await(GestaltOrg.listAccountGrants(newOrg.id, newOrgAct.id)) must containTheSameElementsAs(Seq(
        GestaltRightGrant(null, "grantA", None, newOrgApp.id),
        GestaltRightGrant(null, "grantB", Some("grantBvalue"), newOrgApp.id)
      ), (a: GestaltRightGrant,b: GestaltRightGrant) => (a.grantName == b.grantName && a.grantValue == b.grantValue && a.appId == b.appId))
    }

    "cleanup" in {
      await(GestaltGroup.deleteGroup(unmappedGrpFromRootDir.id,ru,rp)) must beTrue
      await(GestaltOrg.deleteOrg(newOrg.id)) must beTrue
    }

  }

  "Org apps" should {

    val testAppName = "test-app-in-root-org"
    lazy val testApp = await(rootOrg.createApp(GestaltAppCreate(
      name = testAppName
    )))

    "be created properly under orgs" in {
      testApp.isServiceApp must beFalse
      testApp.name must_== testAppName
      testApp.orgId must_== rootOrg.id
    }

    "show up in org app listing after creation" in {
      await(rootOrg.listApps) must contain(testApp)
    }

    "be available by name under org" in {
      await(rootOrg.getAppByName(testAppName)) must beSome(testApp)
    }

    "be available by id" in {
      await(GestaltApp.getById(testApp.id)) must beSome(testApp)
    }

    "properly fail on account creation with no default account store" in {
      await(testApp.createAccount(GestaltAccountCreateWithRights(
        username = "wfail",
        firstName = "Will",
        lastName = "Fail",
        email = "",
        phoneNumber = ""  ,
        credential = GestaltPasswordCredential("letmein"),
        groups = None,
        rights = None
      ))) must throwA[BadRequestException](".*application does not have a default account store.*")
    }

    "properly fail on group creation with no default account store" in {
      await(testApp.createGroup(GestaltGroupCreateWithRights(
        name = "will-fail-group",
        rights = None
      ))) must throwA[BadRequestException](".*specified app does not have a default group store.*")
    }

    "be capable of deletion" in {
      await(GestaltApp.deleteApp(testApp.id, ru, rp)) must beTrue
    }

    "not show up after deletion in org app listing" in {
      await(rootOrg.listApps) must not contain testApp
    }

    "not be available after deletion by id" in {
      await(GestaltApp.getById(testApp.id)) must throwA[UnauthorizedAPIException]
    }

  }

}
