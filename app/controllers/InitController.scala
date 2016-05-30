package controllers

import java.util.UUID

import com.galacticfog.gestalt.security.api.GestaltAPIKey
import com.galacticfog.gestalt.security.api.errors.{UnknownAPIException, BadRequestException}
import com.galacticfog.gestalt.security.data.config.ScalikePostgresDBConnection
import com.galacticfog.gestalt.security.data.domain._
import com.galacticfog.gestalt.security.data.model.{UserAccountRepository, InitSettingsRepository}
import com.galacticfog.gestalt.security.utils.SecureIdGenerator
import com.galacticfog.gestalt.security.{EnvConfig, FlywayMigration}
import org.mindrot.jbcrypt.BCrypt
import play.api._
import play.api.libs.json._
import play.api.mvc._
import com.galacticfog.gestalt.security.api.json.JsonImports._
import com.galacticfog.gestalt.security.data.APIConversions._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

case class InitRequest(username: Option[String] = None,
                       password: Option[String] = None,
                       force: Option[Boolean] = None,
                       fixPermissions: Option[Boolean] = None)

case object InitRequest {
  implicit val initRequestFormat = Json.format[InitRequest]
}

object InitController extends Controller with ControllerHelpers {

  private[this] def isInit() = {
    InitSettings.getInitSettings() map {_.initialized} recover {
      case t: Throwable =>
        Logger.warn("error determining initialization",t)
        false
    } get
  }

  var initialized: Boolean = isInit

  def checkInit() = Action {
    Ok(Json.obj(
      "initialized" -> initialized
    ))
  }

  private[this] def findAccountInRoot(username: String): Try[UserAccountRepository] = for {
    serviceApp <- Try { AppFactory.findServiceAppForFQON("root").get }
    account <- AppFactory.getUsernameInDefaultAccountStore(serviceApp.id.asInstanceOf[UUID], username)
  } yield account

  private[this] def evolveFromBefore4(db: ScalikePostgresDBConnection,
                                  username: String,
                                  maybePassword: Option[String]): Try[UserAccountRepository] = {
    val password = maybePassword getOrElse SecureIdGenerator.genId64(40)
    FlywayMigration.migrate(db, username, password)
    for {
      account <- findAccountInRoot(username)
      updatedAccount <- Try {
        if (maybePassword.isEmpty) account.copy(
          secret = "",
          hashMethod = "disabled"
        ).save() else account
      }
    } yield updatedAccount
  }

  def initialize() = Action(parse.json) { implicit request =>
    // change this to a for-comprehension over Try
    if (isInit) throw new BadRequestException("", "service already initialized", "The service is initialized")

    val ir = validateBody[InitRequest]
    val db = EnvConfig.dbConnection.get
    val currentVersion = FlywayMigration.currentVersion(db)
    val pre4Migration = !currentVersion.exists(_ >= 4)

    val maybeExistingAdmin = for {
      settings <- InitSettings.getInitSettings().toOption
      accountId <- settings.rootAccount
      account <- AccountFactory.find(accountId.asInstanceOf[UUID])
    } yield account

    // get/create "root" account
    val initAccount = (ir.username,maybeExistingAdmin) match {
      case (Some(username),None) if pre4Migration =>
        // schema version 4 will create admin user
        evolveFromBefore4(db, username, ir.password)
      case (None,_) if pre4Migration => Failure(BadRequestException(
        resource = "",
        message = "/init requires username",
        developerMessage = "Initialization requires username and password."
      ))
      case (Some(username),None) if !pre4Migration =>
        FlywayMigration.migrate(db, "", "")
        findAccountInRoot(username)
      case (None, Some(existingAdmin)) => Success(existingAdmin)
      case (Some(username),Some(existingAdmin)) if username != existingAdmin.username => Failure(BadRequestException(
        resource = "",
        message = "username provided but admin user already exist",
        developerMessage = "Initialization does not currently support modifying the admin user."
      ))
      case (Some(username),Some(existingAdmin)) if username == existingAdmin.username =>
        ir.password match {
          case None =>
            // clear admin password
            Try{ existingAdmin.copy(
              secret = "",
              hashMethod = "disabled"
            ).save() }
          case Some(newPassword) =>
            // reset admin password
            Try{ existingAdmin.copy(
              secret = BCrypt.hashpw(newPassword, BCrypt.gensalt()),
              hashMethod = "bcrypt"
            ).save() }
        }
    }

    val keys = for {
      account <- initAccount
      init <- InitSettings.getInitSettings()
      newInit <- Try{
        init.copy(
          initialized = true,
          rootAccount = Some(account.id)
        ).save()
      }
      rootOrg <- Try{ OrgFactory.findByFQON("root").get }
      prevKeys = APICredentialFactory.findByAccountId(account.id.asInstanceOf[UUID])
      apiKeys <- if (prevKeys.isEmpty) APICredentialFactory.createAPIKey(
        accountId = account.id.asInstanceOf[UUID],
        boundOrg = Some(rootOrg.id.asInstanceOf[UUID]),
        parentApiKey = None
      ).map(List(_)) else Success(prevKeys)
    } yield apiKeys

    if (keys.isSuccess) initialized = true
    renderTry[Seq[GestaltAPIKey]](Ok)( keys map {_.map {k => (k: GestaltAPIKey)} } )
  }

}
