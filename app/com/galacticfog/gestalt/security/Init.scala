package com.galacticfog.gestalt.security

import java.util.UUID
import javax.inject.Inject

import com.galacticfog.gestalt.security.api.errors.{BadRequestException, ResourceNotFoundException}
import com.galacticfog.gestalt.security.data.config.ScalikePostgresDBConnection
import com.galacticfog.gestalt.security.data.domain.{APICredentialFactory, AccountFactory, AppFactory, OrgFactory}
import com.galacticfog.gestalt.security.data.model._
import com.galacticfog.gestalt.security.utils.SecureIdGenerator
import modules.DatabaseConnection
import org.mindrot.jbcrypt.BCrypt
import org.postgresql.util.PSQLException
import play.api.Logger
import play.api.libs.json.Json
import scalikejdbc._

import scala.util.{Failure, Success, Try}

case class InitRequest(username: Option[String] = None,
                       password: Option[String] = None,
                       force: Option[Boolean] = None,
                       fixPermissions: Option[Boolean] = None)

case object InitRequest {
  implicit val initRequestFormat = Json.format[InitRequest]
}

class Init @Inject() ( dbConn: DatabaseConnection ) extends SQLSyntaxSupport[InitSettingsRepository] {

  def isInit: Try[Boolean] = checkDBInit

  private[this] def checkDBInit: Try[Boolean] = {
    getInitSettings map {_.initialized} recoverWith {
      case t: PSQLException if t.getSQLState == "42P01" =>
        Logger.info(s"received 42P01 on getInitSettings, assuming uninitialized")
        Success(false)
      case t: Throwable =>
        Logger.error(s"error determining initialization status: ${t.getMessage}", t)
        Failure(t)
    }
  }

  def getInitSettings = Try {
    InitSettingsRepository.find(0).get
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

  def doInit(ir: InitRequest): Try[Seq[APICredentialRepository]] = {
    if (checkDBInit.toOption.contains(true)) Failure(BadRequestException("", "service already initialized", "The service is initialized"))
    else {
      val db = dbConn.dbConnection
      val currentVersion = FlywayMigration.currentVersion(db)
      val pre4Migration = !currentVersion.exists(_ >= 4)

      val maybeExistingAdmin = for {
        settings <- getInitSettings.toOption
        accountId <- settings.rootAccount
        account <- AccountFactory.find(accountId.asInstanceOf[UUID])
      } yield account

      // get/create "root" account
      val initAccount = (ir.username,maybeExistingAdmin) match {
        case (Some(username),None) if pre4Migration =>
          // schema version 4 will create admin user
          evolveFromBefore4(db, username, ir.password)
        case (Some(username),None) if !pre4Migration =>
          FlywayMigration.migrate(db, "", "")
          findAccountInRoot(username) recoverWith {
            case _: ResourceNotFoundException => Failure(BadRequestException(
              resource = "/init",
              message = "invalid username",
              developerMessage = "Admin account username specified to init must be an extant account in the /root organization."
            ))
          }
        case (None, Some(existingAdmin)) =>
          FlywayMigration.migrate(db, "", "")
          Success(existingAdmin)
        case (Some(username),Some(existingAdmin)) if username == existingAdmin.username =>
          ir.password match {
            case None =>
              // don't clear admin password, leave it be
              Success(existingAdmin)
            case Some(newPassword) =>
              // reset admin password
              Try{ existingAdmin.copy(
                secret = BCrypt.hashpw(newPassword, BCrypt.gensalt()),
                hashMethod = "bcrypt"
              ).save() }
          }
        case (None,_) if pre4Migration => Failure(BadRequestException(
          resource = "/init",
          message = "initialization requires username",
          developerMessage = "Initialization requires username and password."
        ))
        case (Some(username),Some(existingAdmin)) if username != existingAdmin.username => Failure(BadRequestException(
          resource = "/init",
          message = "username provided but admin user already exist",
          developerMessage = "Initialization does not currently support modifying the admin user."
        ))
        case (None,None) => Failure(BadRequestException(
          resource = "/init",
          message = "username required to establish admin user",
          developerMessage = "There is no registered admin user, so the username of the desired user is necessary to establish one."
        ))
      }

      for {
        account <- initAccount
        init <- getInitSettings
        _ <- Try{
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
    }
  }

}
