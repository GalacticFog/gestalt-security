package controllers

import com.galacticfog.gestalt.security.api._
import com.galacticfog.gestalt.security.data.config.ScalikePostgresDBConnection
import com.galacticfog.gestalt.security.data.domain._
import com.galacticfog.gestalt.security.data.model.{UserAccountRepository, AppRepository, RightGrantRepository, APIAccountRepository}
import com.galacticfog.gestalt.security.utils.SecureIdGenerator
import play.api.libs.json.{JsValue, Json}
import play.api.{Logger => log}
import org.apache.shiro.SecurityUtils
import org.apache.shiro.config.IniSecurityManagerFactory
import play.api._
import play.api.mvc._
import org.apache.shiro.mgt.SecurityManager
import scala.concurrent.ExecutionContext.Implicits.global
import play.api.Play.current
import com.galacticfog.gestalt.security.data.APIConversions._
import com.galacticfog.gestalt.security.api.json.JsonImports._

import scala.util.{Failure, Success, Try}
import scala.collection.JavaConverters._

case class SecurityControllerInitializationError(msg: String) extends RuntimeException(msg)

object Res {
  val missingShiroManagerType = "application config is missing field \"shiro.managerType\""
  val invalidShiroManagerType = "application config has invalid \"shiro.managerType\": \"%s\""
  val caughtException = "caught exception creating Ini SecurityManager: %s"
}

object RESTAPIController extends Controller with GestaltHeaderAuthentication {

  val db = securityDB

  def initShiro() = {

    def securityMgr: SecurityManager = Play.current.configuration.getString("shiro.managerType") match {
      case Some("INI") => {
        val mgrIni = Play.current.configuration.getString("shiro.iniFile") getOrElse "file:conf/shiro.ini"
        Try {
          val factory = new IniSecurityManagerFactory(mgrIni)
          factory.getInstance()
        } match {
          case Success(sm) => sm
          case Failure(t) => {
            log.error("Caught exception initializing Shiro SecurityManager from IniSecurityManagerFactory", t)
            throw new SecurityControllerInitializationError(Res.caughtException.format(t.getMessage))
          }
        }
      }
      case Some(invalid) => throw new SecurityControllerInitializationError(Res.missingShiroManagerType.format(invalid))
      case None => throw new SecurityControllerInitializationError(Res.missingShiroManagerType)
    }

    SecurityUtils.setSecurityManager(securityMgr)

    //    val sampleRealm = new PlayRealm()
    //    val securityManager = new PlaySecurityManager()
    //    securityManager.setRealm(sampleRealm)
    //
    // Turn off session storage for better "stateless" management.
    // https://shiro.apache.org/session-management.html#SessionManagement-StatelessApplications%2528Sessionless%2529
    //    val subjectDAO = securityManager.getSubjectDAO.asInstanceOf[DefaultSubjectDAO]
    //    val sessionStorageEvaluator = subjectDAO.getSessionStorageEvaluator.asInstanceOf[DefaultSessionStorageEvaluator]
    //
    //    sessionStorageEvaluator.setSessionStorageEnabled(false)
    //
    //    org.apache.shiro.SecurityUtils.setSecurityManager(securityManager)

  }

  def getDefaultOrg() = requireAPIKeyAuthentication { apiAccount => implicit request =>
    GestaltOrgFactory.findByOrgId(apiAccount.defaultOrg) match {
      case Some(org) => Ok(Json.toJson[GestaltOrg](org))
      case None => handleError(ResourceNotFoundException(
        resource = "org",
        message = "could not locate default org",
        developerMessage = "Could not locate a default organization for the authenticate API user."
      ))
    }
  }

  def getOrgById(orgId: String) = requireAPIKeyAuthentication { apiAccount => implicit request =>
    GestaltOrgFactory.findByOrgId(orgId) match {
      case Some(org) => Ok(Json.toJson[GestaltOrg](org))
      case None => handleError(ResourceNotFoundException(
        resource = "org",
        message = "could not locate requested org",
        developerMessage = "Could not locate the requested organization. Make sure to use the organization ID and not the organization name."
      ))
    }
  }

  def listOrgApps(orgId: String) = requireAPIKeyAuthentication { apiAccount => implicit request =>
    Ok(Json.toJson[Seq[GestaltApp]](AppFactory.listByOrgId(orgId) map { a => a: GestaltApp }))
  }

  def getAppById(appId: String) = requireAPIKeyAuthentication { apiAccount => implicit request =>
    AppFactory.findByAppId(appId) match {
      case Some(app) => Ok(Json.toJson[GestaltApp](app))
      case None => handleError(ResourceNotFoundException(
        resource = "app",
        message = "could not locate requested app",
        developerMessage = "Could not locate the requested application. Make sure to use the application ID and not the application name."
      ))
    }
  }

  def listAppUsers(appId: String) = requireAPIKeyAuthentication { apiAccount => implicit request =>
    AppFactory.findByAppId(appId) match {
      case Some(app) => Ok(Json.toJson[Seq[GestaltAccount]](UserAccountFactory.listByAppId(app.appId) map { a => a: GestaltAccount }))
      case None => handleError(ResourceNotFoundException(
        resource = "app",
        message = "could not locate requested app",
        developerMessage = "Could not locate the requested application. Make sure to use the application ID and not the application name."
      ))
    }
  }

  def listAccountRights(appId: String, username: String) = requireAPIKeyAuthentication { apiAccount => implicit request =>
    UserAccountFactory.listAppGrants(appId,username) match {
      case Success(rights) => Ok(Json.toJson[Seq[GestaltRightGrant]](rights map { r => r: GestaltRightGrant}) )
      case Failure(e) => handleError(e)
    }
  }

  def getAccountGrant(appId: String, username: String, grantName: String) = requireAPIKeyAuthentication { apiAccount => implicit request =>
    UserAccountFactory.getAppGrant(appId,username,grantName) match {
      case Success(right) => Ok(Json.toJson[GestaltRightGrant](right))
      case Failure(e) => handleError(e)
    }
  }

  def deleteAccountGrant(appId: String, username: String, grantName: String) = requireAPIKeyAuthentication { apiAccount => implicit request =>
    UserAccountFactory.deleteAppGrant(appId,username,grantName) match {
      case Success(wasDeleted) => Ok(Json.toJson(DeleteResult(wasDeleted)))
      case Failure(e) => handleError(e)
    }
  }

  def updateAccountGrant(appId: String, username: String, grantName: String) = requireAPIKeyAuthentication(parse.json, { apiAccount => implicit request: Request[JsValue] =>
    UserAccountFactory.updateAppGrant(appId,username,grantName,request.body) match {
      case Success(grant) => Ok(Json.toJson[GestaltRightGrant](grant))
      case Failure(e) => handleError(e)
    }
  })

  def appAuth(appId: String) = requireAPIKeyAuthentication(parse.json, { apiAccount => implicit request: Request[JsValue] =>
    val attempt = for {
      app <- AppFactory.findByAppId(appId)
      account <- UserAccountFactory.authenticate(app.appId, request.body)
      rights = RightGrantFactory.listRights(appId = app.appId, accountId = account.accountId)
    } yield (account,rights)
    attempt match {
      case None => handleError(ForbiddenAPIException(
        message = "failed to authenticate application account",
        developerMessage = "Specified credentials did not authenticate an account on the specified application. Ensure that the application ID is correct and that the credentials correspond to an assigned account."
      ))
      case Some((acc,rights)) => Ok(Json.toJson[GestaltAuthResponse](GestaltAuthResponse(acc,rights map {r => r:GestaltRightGrant})))
    }
  })

  def createAppUser(appId: String) = requireAPIKeyAuthentication(parse.json, { apiAccount => implicit request: Request[JsValue] =>
    val userCreate = request.body.validate[GestaltAccountCreate]
    userCreate.fold(
      errors => handleError(BadRequestException(
        resource = "user",
        message = "error parsing JSON",
        developerMessage = "Error parsing JSON payload. Expected a GestaltAccountCreate object."
      )),
      user => {
       AppFactory.createUserInApp(appId, user) match {
         case Success(account) =>  Created( Json.toJson[GestaltAccount](account) )
         case Failure(ex) => handleError(ex)
       }
      }
    )
  })

  def createOrgApp(orgId: String) = requireAPIKeyAuthentication(parse.json, { apiAccount => implicit request: Request[JsValue] =>
    (request.body \ "appName").asOpt[String] match {
      case None => handleError(BadRequestException(
        resource = "app",
        message = "payload did not include application name",
        developerMessage = "JSON payload did not include application name \"appName\""
      ))
      case Some(appName) => {
        AppFactory.findByAppName(orgId,appName) match {
          case Success(app) => handleError(CreateConflictException(
            resource = "app",
            message = "app already exists in org",
            developerMessage = "An application with the specified name already exists in the specified organization. Select a different application name."
          ))
          case Failure(e) => {
            try {
              Created(Json.toJson[GestaltApp](AppRepository.create(appId = SecureIdGenerator.genId62(AppFactory.APP_ID_LEN), appName = appName, orgId = orgId)))
            } catch {
              case t: Throwable => handleError(t)
            }
          }
        }
      }
    }
  })

  private def securityDB = {
    val connection = current.configuration.getObject("database") match {
      case None =>
        throw new RuntimeException("FATAL: Database configuration not found.")
      case Some(config) => {
        val configMap = config.unwrapped.asScala.toMap
        displayStartupSettings(configMap)
        ScalikePostgresDBConnection(
          host = configMap("host").toString,
          database = configMap("dbname").toString,
          port = configMap("port").toString.toInt,
          user = configMap("user").toString,
          password = configMap("password").toString,
          timeoutMs = configMap("timeoutMs").toString.toLong)
      }
    }
    println("CONNECTION : " + connection)
    connection
  }

  private def displayStartupSettings(config: Map[String, Object]) {
    log.debug("DATABASE SETTINGS:")
    for ((k,v) <- config) {
      if (k != "password")
        log.debug("%s = '%s'".format(k, v.toString))
    }
  }

  private[this] def handleError(e: Throwable) = {
    e match {
      case notFound: ResourceNotFoundException => NotFound(Json.toJson(notFound))
      case badRequest: BadRequestException => BadRequest(Json.toJson(badRequest))
      case noauthc: UnauthorizedAPIException => Unauthorized(Json.toJson(noauthc))
      case noauthz: ForbiddenAPIException => Forbidden(Json.toJson(noauthz))
      case conflict: CreateConflictException => Conflict(Json.toJson(conflict))
      case unknown: UnknownAPIException => BadRequest(Json.toJson(unknown)) // not sure why this would happen, but if we have that level of info, might as well use it
      case nope: Throwable => BadRequest(nope.getMessage)
    }
  }


}