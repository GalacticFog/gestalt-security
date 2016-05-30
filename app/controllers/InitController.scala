package controllers

import java.util.UUID

import com.galacticfog.gestalt.security.api.GestaltAPIKey
import com.galacticfog.gestalt.security.api.errors.{UnknownAPIException, BadRequestException}
import com.galacticfog.gestalt.security.data.config.ScalikePostgresDBConnection
import com.galacticfog.gestalt.security.data.domain._
import com.galacticfog.gestalt.security.data.model.{UserAccountRepository, InitSettingsRepository}
import com.galacticfog.gestalt.security.utils.SecureIdGenerator
import com.galacticfog.gestalt.security.{EnvConfig, FlywayMigration}
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

  private[this] def evolveFromLT4(db: ScalikePostgresDBConnection,
                                  username: String,
                                  maybePassword: Option[String]): Try[UserAccountRepository] = {
    val password = maybePassword getOrElse SecureIdGenerator.genId64(40)
    FlywayMigration.migrate(db, username, password)
    for {
      rootOrg <- Try{ OrgFactory.findByFQON("root").get }
      rootDir <- Try { DirectoryFactory.listByOrgId(rootOrg.id.asInstanceOf[UUID]).head}
      account <- Try {
        val account = rootDir.lookupAccountByUsername(username).get
        if (maybePassword.isEmpty) account.copy(
          secret = ""
        ).save() else account
      }
    } yield account
  }

  def initialize() = Action(parse.json) { implicit request =>
    // change this to a for-comprehension over Try
    val ir = validateBody[InitRequest]
    val db = EnvConfig.dbConnection.get
    val currentVersion = FlywayMigration.currentVersion(db)
    val pre4Migration = !currentVersion.exists(_ >= 4)
    if (isInit) throw new BadRequestException("", "service already initialized", "The service is initialized")
    val keys = (ir.username, ir.password) match {
      case (Some(username), maybePassword) if pre4Migration =>
        val initAccount = evolveFromLT4(db, username, maybePassword)
        for {
          rootOrg <- Try{ OrgFactory.findByFQON("root").get }
          account <- initAccount
          key <- APICredentialFactory.createAPIKey(
            accountId = account.id.asInstanceOf[UUID],
            boundOrg = Some(rootOrg.id.asInstanceOf[UUID]),
            parentApiKey = None
          )
        } yield Seq(key)
      case (None,_) if pre4Migration => Failure(BadRequestException("", "/init requires username", "Initialization requires username and password."))
      case (_,_) => Failure(???)
    }

    val init = for {
      keys <- keys
      init <- InitSettings.getInitSettings()
      newInit <- Try{init.copy(
        initialized = true,
        rootAccount = Some(keys(0).accountId)
      ).save()}
    } yield newInit
    if (init.isSuccess) initialized = true
    init match {
      case Success(_) =>
        renderTry[Seq[GestaltAPIKey]](Ok)( keys map {_.map {k => (k: GestaltAPIKey)} } )
      case Failure(err) => throw err
    }
  }

}
