import java.util.UUID

import com.galacticfog.gestalt.security.Global
import com.galacticfog.gestalt.security.api._
import com.galacticfog.gestalt.security.api.errors.{UnauthorizedAPIException, BadRequestException}
import com.galacticfog.gestalt.security.data.domain.{AccountFactory, AppFactory, OrgFactory, DirectoryFactory}
import com.galacticfog.gestalt.security.data.model.UserAccountRepository
import org.specs2.execute.{Results, Result}
import org.specs2.matcher.{MatchResult, Expectable, Matcher, ValueCheck, ValueChecks}
import play.api.libs.ws.WS
import play.api.test._
import com.galacticfog.gestalt.security.data.APIConversions._

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
  lazy val rootDir = DirectoryFactory.listByOrgId(rootOrg.id).head
  lazy val rootAccount: GestaltAccount = rootDir.lookupAccountByUsername(ru).get

  "Service should" should {

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
      val orgs = await(GestaltOrg.getOrgs(ru, rp))
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
      await(GestaltOrg.deleteOrg(rootOrg.id,ru,rp)) must throwA[BadRequestException]
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
      await(GestaltApp.listGrants(rootApp.id, "root") ) must containTheSameElementsAs(appAuth.rights)
      await(GestaltApp.listGrants(rootApp.id, appAuth.account.id)) must containTheSameElementsAs(appAuth.rights)
    }

    "returns the same accounts as the root org" in {
      await(rootApp.listAccounts) must containTheSameElementsAs(await(rootOrg.listAccounts))
    }

    "not be capable of deletion" in {
      await(GestaltApp.deleteApp(rootApp.id, ru, rp)) must throwA[BadRequestException]
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
      await(failure) must throwA[BadRequestException]
    }

    "prohibit a name with a space" in {
      val failure = GestaltOrg.createSubOrg(rootOrg.id, name = "has space")
      await(failure) must throwA[BadRequestException]
    }

    "prohibit a name with preceding dash" in {
      val failure = GestaltOrg.createSubOrg(rootOrg.id, name = "-good-but-for-the-dash")
      await(failure) must throwA[BadRequestException]
    }

    "prohibit a name with a trailing dash" in {
      val failure = GestaltOrg.createSubOrg(rootOrg.id, name = "good-but-for-the-dash-")
      await(failure) must throwA[BadRequestException]
    }

    "prohibit a name with consecutive dash" in {
      val failure = GestaltOrg.createSubOrg(rootOrg.id, name = "good-but-for--the-dash")
      await(failure) must throwA[BadRequestException]
    }

  }

  "New Org Without Directory" should {

    lazy val newOrgName = "neworgnodir"
    lazy val newOrg = await(rootOrg.createSubOrg(GestaltOrgCreate(
      name = newOrgName,
      createDefaultUserGroup = Some(false)
    )))
    lazy val newOrgApp = await(newOrg.getServiceApp())

    "not have root in fqon" in {
      newOrg must haveName(newOrgName)
      newOrg.fqon must_== newOrgName
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

    lazy val newOrgMappings = await(newOrgApp.listAccountStores)
    lazy val newOrgMapping  = newOrgMappings.head
    lazy val newOrgAdminGroup = await(GestaltGroup.getById(newOrgMapping.storeId))

    "contain an account store mapping to a group in the parent directory" in {
      newOrgMappings must haveSize(1)
      newOrgMapping.storeType must_== com.galacticfog.gestalt.security.api.GROUP
      newOrgAdminGroup must beSome

      await(GestaltAccountStoreMapping.getById(newOrgMapping.id)) must beSome(newOrgMapping)
    }

    "list the new group as an org group" in {
      await(newOrg.listGroups()) must contain(exactly(newOrgAdminGroup.get))
    }

    "list the root admin as an org account" in {
      await(newOrg.listAccounts()) must contain(exactly(rootAccount))
    }

    "grant rights to the root admin via the new org admin group" in {
      val accountRights = await(newOrg.listAccountGrants(rootAccount.id))
      val groupRights   = await(newOrg.listGroupGrants(newOrgAdminGroup.get.id))
      accountRights must containTheSameElementsAs(groupRights)
    }

    "be unable to get the root admin by name via the org because there is no default account store" in {
      await(newOrg.getAccountByUsername(rootAccount.username)) must throwA[BadRequestException](".*does not have a default account store.*")
    }

    "get the new group by id via the org" in {
      await(newOrg.getGroupById(newOrgAdminGroup.get.id)) must beSome(newOrgAdminGroup.get)
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

  "Accounts" should {

    "handle appropriately for non-existent lookup" in {
      await(rootOrg.getAccountById(UUID.randomUUID)) must beNone
    }

    "not be able to delete themselves" in {
      await(GestaltAccount.deleteAccount(rootAccount.id, ru, rp)) must throwA[BadRequestException]
    }

  }

}
